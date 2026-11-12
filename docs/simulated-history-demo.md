# Manual simulated price-drop demo (SCRUM-7)

This guide demonstrates the user-visible SCRUM-7 result: an existing phone
moves from outside a user's budget to an active dashboard recommendation after
you inject a simulated price drop in Swagger. SQL is used only for controlled
fixture setup and cleanup.

## Safety boundary

Both conditions are mandatory:

1. Spring must run with the `ingestion-demo` profile.
2. `spring.datasource.url` must be a PostgreSQL URL with an explicit loopback
   host (`localhost`, `127.0.0.1`, or loopback IPv6).

The second check is performed when the simulator service is created and does
not depend on the database name. A Neon or other hosted URL is rejected even
when `ingestion-demo` is active. Without the profile, the service and route do
not exist in the application, including deployed production.

The existing local `techadvisor` database is supported; a separate `_demo` or
`_test` database is not required for a manual demo.

## Manual visual before/after test

The SQL below only prepares and removes local demo fixtures. You perform the
actual price drop yourself in Swagger and observe it in the existing frontend.

### 1. Start the application

From the repository root, start local PostgreSQL:

```powershell
docker compose up -d postgres
```

Start the backend in another terminal. Keep your existing local
`ADMIN_EMAIL`, `ADMIN_PASSWORD`, `JWT_SECRET`, and database credentials; never
substitute a Neon connection.

```powershell
cd backend
$env:SPRING_PROFILES_ACTIVE = 'ingestion-demo'
$env:DB_HOST = '127.0.0.1'
$env:DB_PORT = '5433'
$env:POSTGRES_DB = 'techadvisor'
$env:INGESTION_SCHEDULING_ENABLED = 'false'
$env:RECOMMENDATION_AI_ASSESSMENT_ENABLED = 'false'
.\mvnw.cmd spring-boot:run
```

Disabling AI makes this visual test reliable and cost-free. The deterministic
recommendation pipeline still runs normally.

Start the existing frontend in a third terminal:

```powershell
cd frontend
npm install # first run only
npm run dev
```

Open the URL printed by Vite, normally `http://localhost:5173`.

### 2. Reuse or register the demo account

Use the frontend to sign in as:

```text
Email: test@example.com
Password: the account's existing local password
```

If the account does not exist yet, register `test@example.com` through the
normal frontend registration page with any local password of at least eight
characters. Later runs reuse the same account and password.

Do not add a device in the frontend. The setup SQL creates the controlled
device and budget without firing an initial recommendation trigger. For safety,
setup stops if this account already owns any device; run **Clean up the demo**
if the existing device is from an interrupted demo.

### 3. Run the local safety preflight

Open `psql`, pgAdmin, or another SQL console connected specifically to local
`techadvisor`:

```powershell
psql -h 127.0.0.1 -p 5433 -U <LOCAL_POSTGRES_USER> -d techadvisor
```

Run:

```sql
SELECT count(*) AS other_evaluable_smartphones
FROM user_devices d
JOIN products p ON p.id = d.product_id
JOIN device_preferences pref ON pref.user_device_id = d.id
JOIN users u ON u.id = d.user_id
WHERE d.is_current
  AND p.category = 'SMARTPHONE'
  AND NOT (
      u.email = 'test@example.com'
      AND d.custom_name = 'SCRUM-7 older phone'
      AND pref.notes = 'SCRUM-7 local visual demo'
  );
```

Continue only when the result is `0`. Otherwise stop: the price event could
re-evaluate another local user's device.

### 4. Prepare one predictable phone pair

Paste this block into the same **local** SQL console. It selects an existing
older phone and a later phone with clearly stronger specifications. It creates
only a temporary user-device fixture and a source-labelled S$1,300 setup price;
it does not change any product or genuine observation.

```sql
BEGIN;

CREATE TEMP TABLE scrum7_pair ON COMMIT DROP AS
SELECT
    owned.id AS owned_product_id,
    owned.brand || ' ' || owned.model_name AS owned_phone,
    owned.release_date AS owned_release_date,
    candidate.id AS candidate_product_id,
    candidate.brand || ' ' || candidate.model_name AS candidate_phone
FROM products owned
JOIN phone old_spec ON old_spec.product_id = owned.id
JOIN products candidate ON candidate.id <> owned.id
JOIN phone new_spec ON new_spec.product_id = candidate.id
WHERE owned.category = 'SMARTPHONE'
  AND candidate.category = 'SMARTPHONE'
  AND owned.status = 'VERIFIED'
  AND candidate.status = 'VERIFIED'
  AND owned.release_date IS NOT NULL
  AND candidate.release_date > owned.release_date
  AND old_spec.battery_mah > 0
  AND old_spec.ram_gb > 0
  AND old_spec.storage_gb > 0
  AND old_spec.refresh_rate_hz > 0
  AND new_spec.battery_mah > old_spec.battery_mah
  AND new_spec.ram_gb >= old_spec.ram_gb
  AND new_spec.storage_gb > old_spec.storage_gb
  AND new_spec.refresh_rate_hz >= old_spec.refresh_rate_hz
  AND EXISTS (SELECT 1 FROM price_history ph WHERE ph.product_id = candidate.id)
ORDER BY
    candidate.release_date - owned.release_date DESC,
    new_spec.storage_gb - old_spec.storage_gb DESC,
    new_spec.battery_mah - old_spec.battery_mah DESC
LIMIT 1;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM users WHERE email = 'test@example.com'
    ) THEN
        RAISE EXCEPTION 'Register or restore test@example.com first';
    END IF;
    IF EXISTS (
        SELECT 1 FROM user_devices d
        JOIN users u ON u.id = d.user_id
        WHERE u.email = 'test@example.com'
    ) OR EXISTS (
        SELECT 1 FROM price_history
        WHERE source = 'simulated-demo-setup:scrum7-visual-v1'
    ) THEN
        RAISE EXCEPTION 'Account already owns a device or old demo data exists; do not overwrite it';
    END IF;
    IF (SELECT count(*) FROM scrum7_pair) <> 1 THEN
        RAISE EXCEPTION 'No suitable existing smartphone pair was found';
    END IF;
END $$;

INSERT INTO price_history (product_id, price, currency, source, observed_at)
SELECT candidate_product_id, 1300.00, 'SGD',
       'simulated-demo-setup:scrum7-visual-v1', CURRENT_TIMESTAMP
FROM scrum7_pair;

WITH new_device AS (
    INSERT INTO user_devices (
        user_id, product_id, custom_name, purchase_date, condition,
        satisfaction_score, use_cases, is_current
    )
    SELECT
        u.id, pair.owned_product_id, 'SCRUM-7 older phone',
        pair.owned_release_date, 'FAIR', 45,
        '["gaming","photography"]'::jsonb, true
    FROM users u CROSS JOIN scrum7_pair pair
    WHERE u.email = 'test@example.com'
    RETURNING id
)
INSERT INTO device_preferences (
    user_device_id, budget, currency, upgrade_urgency,
    brand_flexibility, priorities, pain_points, notes
)
SELECT
    id, 1200.00, 'SGD', 'SOMEWHAT_URGENT', 'FLEXIBLE',
    '{"battery":5,"performance":4,"camera":3}'::jsonb,
    '["Battery drains quickly","Performance is slowing down"]'::jsonb,
    'SCRUM-7 local visual demo'
FROM new_device;

SELECT owned_product_id, owned_phone, candidate_product_id, candidate_phone,
       1300.00 AS before_price, 1200.00 AS user_budget
FROM scrum7_pair;

COMMIT;
```

Save the displayed `candidate_product_id` and `candidate_phone`.

### 5. Confirm the before state in the frontend

Log in as `test@example.com` and open the dashboard. The selected
candidate must not be shown because its S$1,300 setup price exceeds the
S$1,200 budget. Leave this tab open.

### 6. Sign into Swagger as the local admin

Open `http://localhost:8080/swagger-ui.html`:

1. Open `POST /api/auth/login`, select **Try it out**, and enter your configured
   **local** admin credentials:

   ```json
   {
     "email": "<LOCAL_ADMIN_EMAIL>",
     "password": "<LOCAL_ADMIN_PASSWORD>"
   }
   ```

2. Confirm the response says `"role": "ADMIN"`, then copy its `token`.
3. Select **Authorize**, paste the token, and select **Authorize** again.

Swagger adds the `Bearer` prefix, so paste only the token unless your Swagger
version explicitly asks for the complete bearer value.

### 7. Inject the price drop manually

Open `POST /api/admin/simulation/changes/price`, select **Try it out**, and use
the candidate ID printed by the setup query:

```json
{
  "productId": <CANDIDATE_PRODUCT_ID>,
  "newPrice": 1100.00,
  "idempotencyKey": "scrum7-visual-price-drop-v1"
}
```

Select **Execute**. The first response should be `201 Created` and contain:

- `eventType: "PRICE_CHANGE"`;
- `oldValue.price: 1300.00` and `newValue.price: 1100.00`;
- `alreadyApplied: false`;
- a `marketEventId` and a `source` beginning with `simulated-change:price:`.

Copy the exact `source`; cleanup uses it. Wait a few seconds for the existing
asynchronous trigger, then manually refresh the dashboard. The saved candidate
should now appear at S$1,100 with its deterministic recommendation verdict.
There is no live auto-refresh for this ticket.

Optional idempotency check: execute the exact same request again. It should
return `200 OK` with `alreadyApplied: true` and create no duplicate change or
recommendation trigger.

### 8. Clean up the demo

Repeat the safety-preflight query from step 3; it must still return `0`. In
`psql`, replace the placeholder below with the exact `source` copied from the
Swagger response. Do not run the block while `<...>` is still present.

```sql
\set drop_source 'simulated-change:price:<PRODUCT_ID>:<HASH_FROM_SWAGGER>'

BEGIN;

CREATE TEMP TABLE scrum7_sources(source text PRIMARY KEY) ON COMMIT DROP;
INSERT INTO scrum7_sources VALUES
    ('simulated-demo-setup:scrum7-visual-v1'),
    (:'drop_source');

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM scrum7_sources
        WHERE source LIKE '%<%'
           OR source NOT LIKE 'simulated-%'
    ) THEN
        RAISE EXCEPTION 'Replace the source placeholder before cleanup';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM user_devices d
        JOIN products p ON p.id = d.product_id
        JOIN device_preferences pref ON pref.user_device_id = d.id
        JOIN users u ON u.id = d.user_id
        WHERE d.is_current
          AND p.category = 'SMARTPHONE'
          AND NOT (
              u.email = 'test@example.com'
              AND d.custom_name = 'SCRUM-7 older phone'
              AND pref.notes = 'SCRUM-7 local visual demo'
          )
    ) THEN
        RAISE EXCEPTION 'Another local smartphone user could have been affected';
    END IF;
END $$;

CREATE TEMP TABLE scrum7_account ON COMMIT DROP AS
SELECT id FROM users WHERE email = 'test@example.com';

CREATE TEMP TABLE scrum7_devices ON COMMIT DROP AS
SELECT d.id
FROM user_devices d
JOIN device_preferences pref ON pref.user_device_id = d.id
WHERE d.user_id IN (SELECT id FROM scrum7_account)
  AND d.custom_name = 'SCRUM-7 older phone'
  AND pref.notes = 'SCRUM-7 local visual demo';

CREATE TEMP TABLE scrum7_events ON COMMIT DROP AS
SELECT id FROM market_events WHERE source IN (SELECT source FROM scrum7_sources);

CREATE TEMP TABLE scrum7_recommendations ON COMMIT DROP AS
SELECT id FROM recommendations
WHERE current_device_id IN (SELECT id FROM scrum7_devices);

CREATE TEMP TABLE scrum7_notifications ON COMMIT DROP AS
SELECT id FROM notifications
WHERE recommendation_id IN (SELECT id FROM scrum7_recommendations);

DELETE FROM system_log
WHERE (component = 'TRIGGER_MARKET_EVENT'
       AND metadata ->> 'market_event_id' IN
           (SELECT id::text FROM scrum7_events))
   OR (component = 'TRIGGER_DEVICE_INVENTORY'
       AND metadata ->> 'user_device_id' IN
           (SELECT id::text FROM scrum7_devices))
   OR (component = 'RECOMMENDATION_EMAIL'
       AND metadata ->> 'recommendationId' IN
           (SELECT id::text FROM scrum7_recommendations))
   OR (component = 'NOTIFICATIONS'
       AND metadata ->> 'current_device_id' IN
           (SELECT id::text FROM scrum7_devices));

DELETE FROM recommendation_dismissals
WHERE recommendation_id IN (SELECT id FROM scrum7_recommendations);

DELETE FROM notifications WHERE id IN (SELECT id FROM scrum7_notifications);
DELETE FROM recommendations WHERE id IN (SELECT id FROM scrum7_recommendations);
DELETE FROM user_devices WHERE id IN (SELECT id FROM scrum7_devices);
DELETE FROM market_events WHERE id IN (SELECT id FROM scrum7_events);
DELETE FROM price_history WHERE source IN (SELECT source FROM scrum7_sources);

COMMIT;
```

The account row is never deleted or updated. Cleanup selects the temporary
device by the exact account email, device name, and preference-note marker;
then it removes only recommendations, notifications, dismissals, logs, and
preferences linked to that device. The two exact source labels remove only the
setup and injected price rows/event. Products, specifications, genuine
observations, other accounts, and unrelated data owned by `test@example.com`
are untouched.

Verify that the account count is `1` and every demo-data count is `0`:

```sql
SELECT
    (SELECT count(*) FROM users
     WHERE email = 'test@example.com') AS preserved_account,
    (SELECT count(*)
     FROM user_devices d
     JOIN device_preferences pref ON pref.user_device_id = d.id
     JOIN users u ON u.id = d.user_id
     WHERE u.email = 'test@example.com'
       AND d.custom_name = 'SCRUM-7 older phone'
       AND pref.notes = 'SCRUM-7 local visual demo') AS demo_devices,
    (SELECT count(*) FROM price_history
     WHERE source = 'simulated-demo-setup:scrum7-visual-v1') AS setup_prices,
    (SELECT count(*) FROM price_history
     WHERE source = :'drop_source') AS drop_prices,
    (SELECT count(*) FROM market_events
     WHERE source = :'drop_source') AS drop_events;
```

The automated equivalent is `SimulatedRecommendationWorkflowIntegrationTest`.
It uses a dedicated `_test` database and does not touch the normal development
database.
