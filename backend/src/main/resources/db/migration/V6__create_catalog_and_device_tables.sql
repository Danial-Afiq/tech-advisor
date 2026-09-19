-- The four tables candidate shortlisting reads: what exists to buy, what it
-- costs, what the user already owns, and what they want from it.
--
-- Columns follow the canonical design in AGENTS.md sections 14.2, 14.3, 14.4
-- and 14.6. Scope is deliberately limited to what SCRUM-34 needs: the filter
-- matches on category and price, so smartphone_specs, benchmark_results and
-- market_events are left to the tickets that actually consume them.
--
-- RECONCILIATION NOTE: the products block below is copied verbatim from
-- feat/3.4-llm_layer's V5__create_review_corpus.sql. Both branches need the
-- table and neither can assume the other merged first, so the definitions are
-- kept byte-identical: whichever branch merges second deletes its own copy of
-- this one block rather than reconciling two different shapes. Do not "improve"
-- it here in isolation.

CREATE TABLE products (
    id            BIGSERIAL   PRIMARY KEY,
    brand         TEXT        NOT NULL,
    model_name    TEXT        NOT NULL,
    category      TEXT        NOT NULL DEFAULT 'SMARTPHONE',
    release_date  DATE,
    status        TEXT        NOT NULL DEFAULT 'VERIFIED',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT products_brand_model_unique UNIQUE (brand, model_name)
);

COMMENT ON COLUMN products.release_date IS
    'Nullable on purpose. The maturity gate must handle an unknown release '
    'date rather than assume one.';

COMMENT ON COLUMN products.status IS
    'VERIFIED or UNVERIFIED. Nothing writes UNVERIFIED yet - ingestion does '
    'not populate this table on this branch - but shortlisting already '
    'excludes it, so a low-confidence catalogue row can never reach a user by '
    'default. SCRUM-69 establishes the same rule for specification prefill.';

-- Price observations over time rather than one mutable current price: a
-- dropped price is the most common reason an upgrade becomes worth raising,
-- and that is only visible if the history is kept.
CREATE TABLE price_history (
    id          BIGSERIAL     PRIMARY KEY,
    product_id  BIGINT        NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    price       NUMERIC(12,2) NOT NULL CHECK (price >= 0),
    currency    CHAR(3)       NOT NULL,
    source      TEXT,
    observed_at TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON COLUMN price_history.observed_at IS
    'When the source observed this price, not when we ingested it.';

CREATE TABLE user_devices (
    id                 BIGSERIAL   PRIMARY KEY,
    user_id            BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    product_id         BIGINT      REFERENCES products (id) ON DELETE SET NULL,
    custom_name        TEXT,
    purchase_date      DATE,
    condition          TEXT,
    satisfaction_score INTEGER     CHECK (satisfaction_score BETWEEN 0 AND 100),
    use_cases          JSONB       NOT NULL DEFAULT '[]',
    is_current         BOOLEAN     NOT NULL DEFAULT TRUE,
    spec_overrides     JSONB       NOT NULL DEFAULT '{}',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON COLUMN user_devices.product_id IS
    'Nullable catalogue link (AGENTS.md 14.3): a user may own something we do '
    'not stock. Shortlisting needs the owned product''s category, so a device '
    'without this link is rejected rather than queried with a null category.';

COMMENT ON COLUMN user_devices.spec_overrides IS
    'Lets one user''s exact configuration differ from the shared catalogue '
    'entry without mutating the global product.';

-- Preferences belong to the specific owned device, not to the user: someone
-- may want very different things from a phone than from a laptop. This is the
-- design that replaced the older global user_profiles concept.
CREATE TABLE device_preferences (
    user_device_id    BIGINT        PRIMARY KEY
                      REFERENCES user_devices (id) ON DELETE CASCADE,
    budget            NUMERIC(12,2) NOT NULL CHECK (budget >= 0),
    currency          CHAR(3)       NOT NULL DEFAULT 'SGD',
    upgrade_urgency   TEXT,
    brand_flexibility TEXT,
    priorities        JSONB         NOT NULL DEFAULT '{}',
    pain_points       JSONB         NOT NULL DEFAULT '{}',
    notes             TEXT,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON COLUMN device_preferences.budget IS
    'NOT NULL deliberately: the budget ceiling is the whole basis of candidate '
    'shortlisting, and a preferences row without one cannot be evaluated. '
    'FLAG FOR SCRUM-20 (device inventory management): the preferences UI must '
    'always collect a budget. If the product decides a budget is optional, '
    'relax this in a later migration rather than editing this one.';

COMMENT ON COLUMN device_preferences.currency IS
    'Persisted, but NOT used for conversion. Budget and price are compared as '
    'bare numbers on a single-currency assumption; a mismatch is logged, never '
    'converted. Unresolved decision - see AGENTS.md 27.';

-- Named by the ticket's pull-request checklist.
CREATE INDEX idx_product_category     ON products (category);
CREATE INDEX idx_price_history_lookup ON price_history (product_id, observed_at DESC);
CREATE INDEX idx_user_devices_user    ON user_devices (user_id);
