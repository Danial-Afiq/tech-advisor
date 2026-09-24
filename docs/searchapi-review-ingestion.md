# SearchAPI customer reviews into pgvector

This slice attaches owner evidence to canonical VERIFIED smartphones. A named admin
run may also create the canonical product and phone subtype after SearchAPI validates
the provider identity selected by the admin. It does not choose recommendation candidates, score upgrades
or call an LLM. No SearchAPI or embedding work runs on a recommendation request path.

## Implemented flow and persistence

`admin ingestion run -> SearchApiSource -> canonical products ORDER BY id -> token
cache/discovery -> most_relevant + most_recent -> normalize/filter/dedupe ->
ReviewBatch -> ReviewBatchSink -> FastAPI /internal/embed -> transactional
review_documents + review_chunks -> existing PgVectorStore`.

The adapter uses the existing source registry, runner, SourceContext, cooldown and
sink. One product batch is one runner item: acceptance means the entire new batch
has committed. Existing fingerprints are filtered before embedding. No external
call occurs inside a database transaction; a failed embedding writes no corpus,
and a failed insert rolls the entire batch back. Earlier successfully committed
products remain if a later product fails. Mapping writes are independent cache
updates and may survive a failed corpus write.

V7 adds `external_product_mapping` keyed by product/provider/gl/hl/location, plus
nullable `review_documents.provider` and `external_fingerprint`, JSONB `metadata`
and a unique external-identity index. No old migration changes. Existing demo
documents and `ai/scripts/ingest.py` remain compatible. No review tables are cleared.

Each accepted customer review gets one document and an index-0 chunk. The document
contains the canonical product ID, provider, title, fingerprint, exact `ingested_at`
and metadata allowlisting `source_domain`, `rating`, `raw_date`, `retrieved_at`.
`source_name` is `Google Shopping reviews via SearchAPI / <domain>`; downstream
source typing recognizes it as `USER_REVIEW`. `source_url` is NULL because no stable
review URL is assumed. `published_at` is NULL; source date strings are never made
into invented dates. Username/avatar/profile fields and raw JSON are discarded.

FastAPI's protected `/internal/embed` accepts `{"texts":["..."]}` and returns
`{"embedder":"minishlab/potion-retrieval-32M","dimension":512,"vectors":[[...]]}`.
It uses the same cached configured embedder as `/assess`, including Model2Vec's
batch method. It never constructs the LLM. Limits: 1–100 nonblank texts, each at
most 8000 characters. Spring checks vector count, dimension, finite nonzero values
and the expected vector-space identifier before writing. The returned model ID,
not the selector `model2vec`, is stored on every chunk.

## Matching, normalization and quota

Matching requires canonical brand/model tokens and contiguous ordered model text.
Extra words must be known storage/color/carrier/device suffixes, with core tokens
comprising at least 40% of the title. Accessories, refurbished/used phones, unknown
suffixes, repeated core tokens, conflicting variants (e.g. Pro Max vs Pro), and
missing brand/model tokens are rejected. When valid results contain variants, the
identity whose title has the fewest extra suffix tokens wins for untargeted runs.
Manual UI discovery instead returns up to 20 valid, provider-ranked identities for the
admin to choose. The browser receives titles and external IDs only, never product tokens.
The run re-fetches results and accepts only the chosen ID if it still passes the same
matcher. Repeated listings of one Google ID use the same specificity rule, then title
order as a stable tiebreaker.

Mappings record matched title, external product ID, token, canonical name, locale,
status and match/verification times. Canonical-name/locale changes miss the cache.
Only a clear HTTP 400 mentioning an invalid/expired `product_token` triggers cache
invalidation and one rediscovery/retry. Generic 400, auth, quota and transport failures
do not trigger discovery. A newly discovered invalid token is invalidated and fails.
There is no cache TTL or refresh scheduler in this ticket.

Normalization is Unicode NFKC, trimming and whitespace collapsing, with literal
prompt delimiters removed. Domain names are lowercased with `www.` removed; paths
and profiles are not retained. Ratings must be numeric in [1,5]. Blank or shorter
than 20-character text, narrowly recognizable shipping/store-only remarks, invalid
domains/ratings, text over 8000 characters, titles over 500 and dates over 100 are
discarded. Substantive product experiences remain. At most 100 unique reviews per
product are retained, ordered by fingerprint, from the two returned pages.

Fingerprint: SHA-256 over UTF-8 length-prefixed fields: canonical product ID,
source domain, title, text and normalized decimal rating. Text fields use NFKC,
whitespace normalization and `Locale.ROOT` lowercase. Dates, retrieval times and
usernames never participate. Database uniqueness also protects concurrent reruns.

Normal source-run quota: **3 successful searches uncached, 2 cached**, no pagination.
The manual product picker adds one preview search, so a complete selected-product flow
uses four successful searches when uncached.
Default one product/run; configuration allows at most two. The existing HTTP rules
still apply: 60-second source budget, ten total attempts, one-second pacing,
five-second connect and 20-second request timeout, 1 MiB responses, bounded 429/503
retries and Retry-After cooldown. Invalid-token recovery can add searches within
the same cap. Completed runs use the normal 15-minute source cooldown; transport
failures use one minute, while validation/no-match failures are immediately retryable.
No live SearchAPI request occurs in automated tests. Maven test configuration
clears live source selection and credentials and disables scheduling.

Manual UI runs can override that default: check **SearchAPI customer reviews**, enter
the brand followed by the full model, click **Find matching products**, and select one
result. Matching against the local catalogue ignores case and repeated whitespace.
The selected external ID is durable run metadata and participates in idempotency.
The worker re-fetches SearchAPI results and requires that exact ID to remain a valid
brand/model match before caching its server-only token. Unknown names then create the
VERIFIED product and phone subtype transactionally. Missing/stale selections and known
ineligible products fail closed.

To use the frontend after the local setup below, set root `.env`
`VITE_INGESTION_DEMO=true` and `VITE_API_BASE_URL=http://localhost:18087`, then run
`npm run dev` from `frontend/`. Open `http://localhost:5173/admin/ingestion`, connect
with the demo admin password, check **SearchAPI customer reviews**, enter the name,
click **Find matching products**, choose a result, and click **Run now**. If the checkbox is disabled, enable the source in the backend
configuration and restart it. The outcome-based source cooldown still applies.
This replaces the helper in step 9 when using the UI; remaining SQL/retrieval checks
are the same. No additional migration is required for the optional JSONB run metadata.

## Optional live smoke test — PowerShell, local only

Prerequisites: Docker Desktop, Java 21. Run commands from the repository root
unless a step says otherwise. These examples use the local development PostgreSQL
account `techadvisor` / `devpassword` and host port **5433**. If your existing volume
has different credentials, substitute those local values throughout. This procedure
uses a separate `techadvisor_searchapi_demo` database and leaves other databases alone.

1. Sign in to [SearchAPI](https://www.searchapi.io/), obtain your dashboard API key,
   and edit the **repo-root** `.env` (never a Vite-prefixed variable):

   ```powershell
   if (!(Test-Path .env)) { Copy-Item .env.example .env }
   notepad .env
   ```

   Set `SEARCHAPI_API_KEY` to your key privately. Keep one root `.env`; do not put
   the key in shell command arguments, source, screenshots or this guide.

2. Set these other root `.env` values. Leave model-provider keys blank if desired;
   this test calls no LLM. Preserve any other settings you use.

   ```dotenv
   POSTGRES_DB=techadvisor_searchapi_demo
   POSTGRES_USER=techadvisor
   POSTGRES_PASSWORD=devpassword
   DB_HOST=localhost
   DB_PORT=5433
   VECTOR_STORE=pgvector
   EMBEDDER=model2vec
   AI_INGESTION_EMBEDDER=minishlab/potion-retrieval-32M
   AI_SERVICE_URL=http://localhost:8000
   AI_PORT=8000
   SEARCHAPI_GL=sg
   SEARCHAPI_HL=en
   SEARCHAPI_LOCATION=Singapore
   SEARCHAPI_MAX_PRODUCTS_PER_RUN=1
   INGESTION_SCHEDULING_ENABLED=false
   INGESTION_ENABLED_SOURCES=searchapi-google-product-reviews
   ```

   Ensure `JWT_SECRET` is valid Base64 encoding at least 32 random bytes;
   `AI_SERVICE_TOKEN` is a shared random value; `INGESTION_DEMO_PASSWORD` has at least
   12 characters. If these are blank/absent, this snippet fills them without printing
   their values (existing nonblank values are kept):

   ```powershell
   $text = [IO.File]::ReadAllText((Join-Path $PWD '.env'))
   foreach ($name in @('JWT_SECRET','AI_SERVICE_TOKEN','INGESTION_DEMO_PASSWORD')) {
       if ($text -notmatch "(?m)^$name=\S+") {
           $bytes = New-Object byte[] 32
           $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
           $rng.GetBytes($bytes); $rng.Dispose()
           $line = $name + '=' + [Convert]::ToBase64String($bytes)
           if ($text -match "(?m)^$name=.*$") {
               $text = [regex]::Replace($text, "(?m)^$name=.*$", $line)
           } else { $text += "`r`n$line`r`n" }
       }
   }
   [IO.File]::WriteAllText((Join-Path $PWD '.env'), $text)
   ```

3. Start PostgreSQL and ensure the dedicated demo database exists. PostgreSQL's
   `POSTGRES_DB` initialization does not recreate a database on an existing volume.

   ```powershell
   docker compose up -d postgres
   @'
   SELECT 'CREATE DATABASE techadvisor_searchapi_demo'
   WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname='techadvisor_searchapi_demo')
   \gexec
   '@ | docker exec -i tech-advisor-postgres psql -U techadvisor -d postgres -v ON_ERROR_STOP=1
   ```

4. Apply Flyway through the backend project, including pgvector V4, corpus V6 and V7.
   The password shown here is the disposable local example, not a production secret.

   ```powershell
   cd backend
   .\mvnw.cmd flyway:migrate '-Dflyway.url=jdbc:postgresql://localhost:5433/techadvisor_searchapi_demo' '-Dflyway.user=techadvisor' '-Dflyway.password=devpassword'
   cd ..
   docker exec tech-advisor-postgres psql -U techadvisor -d techadvisor_searchapi_demo -c 'SELECT version,success FROM flyway_schema_history ORDER BY installed_rank'
   ```

   Expect successful versions 1–7. Do not alter old migrations to resolve a checksum
   failure in a database created by another branch. Use this fresh demo database.

5. Build/start FastAPI with the root `.env` configuration. The existing image build
   bakes the 512-dimensional model; this may download model weights once, without
   spending SearchAPI or LLM quota. Prewarm the embedder before the bounded run.

   ```powershell
   docker compose up -d --build ai
   Invoke-RestMethod http://localhost:8000/health
   docker compose exec ai python -c 'from app.config import Settings; from app.retrieval.embedder import build_embedder; s=Settings(); e=build_embedder(s.embedder,s.embedding_dim,s.embedding_model_path); print(e.name,e.dim)'
   ```

   Expect `minishlab/potion-retrieval-32M 512`. This checks the baked model in a
   separate process; the first endpoint call still initializes its cached wrapper.

6. In another terminal, start Spring Boot on a separate local port. Flyway also
   validates/applies migrations on startup. The demo profile binds localhost;
   existing production admin routes remain denied.

   ```powershell
   cd D:\SMU\Y2Sem1\CS203\Project\tech-advisor\backend
   $env:SPRING_PROFILES_ACTIVE = 'ingestion-demo'
   $env:SERVER_PORT = '18087'
   .\mvnw.cmd spring-boot:run
   ```

   Wait for startup, then `Invoke-RestMethod http://localhost:18087/actuator/health`
   should return `UP`. If your shell exports conflicting source/DB settings, clear
   those overrides or align them with `.env` before startup.

7. Seed one real model identity into this **local demo database only**. No catalogue
   seed exists on main. The SQL refuses any database name other than this demo and
   refuses a different eligible smartphone; it never changes existing canonical data.

   ```powershell
   @'
   BEGIN;
   DO $$ BEGIN
     IF current_database() <> 'techadvisor_searchapi_demo' THEN
       RAISE EXCEPTION 'Local demo database required';
     END IF;
     IF EXISTS (SELECT 1 FROM products WHERE status='VERIFIED' AND category='SMARTPHONE'
                AND (brand <> 'Apple' OR model_name <> 'iPhone 16 Pro')) THEN
       RAISE EXCEPTION 'Use a fresh demo database with only the intended phone';
     END IF;
   END $$;
   INSERT INTO products(brand,model_name,category,status)
   VALUES ('Apple','iPhone 16 Pro','SMARTPHONE','VERIFIED')
   ON CONFLICT (brand,model_name) DO NOTHING;
   INSERT INTO phone(product_id)
   SELECT id FROM products WHERE brand='Apple' AND model_name='iPhone 16 Pro'
   ON CONFLICT DO NOTHING;
   COMMIT;
   SELECT id,brand,model_name,status,category FROM products ORDER BY id;
   '@ | docker exec -i tech-advisor-postgres psql -U techadvisor -d techadvisor_searchapi_demo -v ON_ERROR_STOP=1
   ```

   Record that product ID. No phone specifications or recommendation claims are
   invented. SearchAPI may have no unambiguous SG listing/reviews for this model;
   a visible no-match/ambiguous result is expected fail-closed behavior.

8. Confirm the root `.env` contains
   `INGESTION_ENABLED_SOURCES=searchapi-google-product-reviews` and the backend was
   started after that edit. The demo profile now honors this setting instead of
   forcing simulated sources. Scheduling remains disabled.

9. Trigger the existing admin API using the checked-in helper:

   ```powershell
   .\scripts\searchapi-smoke.ps1 -BaseUrl http://localhost:18087
   ```

   Enter username `demo-admin` and the root `.env` `INGESTION_DEMO_PASSWORD` when
   prompted. The helper performs `GET /session` for CSRF/cookies, checks `/sources`,
   posts `{"sources":["searchapi-google-product-reviews"],"reason":"Local SearchAPI review smoke test"}`
   to `/api/admin/ingestion/runs` with a fresh `Idempotency-Key` and CSRF header, then
   polls `/runs/{runId}`. It never reads/sends the SearchAPI key itself.

10. A new successful nonempty product batch shows `status: SUCCESS`,
    `processedPayloadCount: 1`, `errorCount: 0`, and this source's `SUCCESS` result.
    An unchanged rerun shows `duplicatePayloadCount: 1`, `processedPayloadCount: 0`.
    Zero accepted and zero duplicate batches means no usable reviews were returned;
    that is not proof of a populated corpus. Check the rows below.

11. Verify corpus/provenance/vector state and profile-field omission:

    ```powershell
    @'
    SELECT d.id,d.product_id,d.source_name,d.title,d.published_at,d.ingested_at,
           d.external_fingerprint,d.metadata,c.chunk_index,c.chunk_text,c.embedder,
           c.embedding IS NOT NULL AS has_embedding,vector_dims(c.embedding) AS dims
    FROM review_documents d JOIN review_chunks c ON c.review_document_id=d.id
    WHERE d.provider='SEARCHAPI_GOOGLE_SHOPPING' ORDER BY d.id;
    SELECT count(*) AS unexpected_metadata FROM review_documents d
    WHERE provider='SEARCHAPI_GOOGLE_SHOPPING'
      AND EXISTS (SELECT 1 FROM jsonb_object_keys(d.metadata) k
                  WHERE k NOT IN ('source_domain','rating','raw_date','retrieved_at'));
    SELECT product_id,provider,gl,hl,location,matched_title,status,matched_at,last_verified_at
    FROM external_product_mapping;
    '@ | docker exec -i tech-advisor-postgres psql -U techadvisor -d techadvisor_searchapi_demo
    ```

    Expect NULL `published_at` for relative dates, `has_embedding=t`, `dims=512`,
    the full model ID, index 0, and `unexpected_metadata=0`. No username/profile
    columns or metadata are written; freeform review text itself remains evidence.
    The mapping query deliberately does not display cached product tokens.

12. Run real semantic retrieval (replace `1` with step 7's ID):

    ```powershell
    docker compose exec ai python -m scripts.verify_searchapi --product-id 1 --query 'battery life' --other-product-id 987654321
    ```

13. Expect JSON listing this product's SearchAPI chunk IDs, source and review text,
    the actual embedder ID and `cross_product_leak: false`. Battery experiences
    should rank near the top if present; live review content/rank is not guaranteed.
    The command fails if it retrieves no SearchAPI evidence or if those IDs leak to
    the unrelated product query. It directly uses the existing `PgVectorStore`.

14. Check counts, **wait for the 15-minute successful-run cooldown**, rerun step 9,
    then repeat the counts. The helper reports the exact next allowed timestamp.

    ```powershell
    docker exec tech-advisor-postgres psql -U techadvisor -d techadvisor_searchapi_demo -c "SELECT count(DISTINCT d.id) AS documents,count(c.id) AS chunks FROM review_documents d LEFT JOIN review_chunks c ON c.review_document_id=d.id WHERE d.provider='SEARCHAPI_GOOGLE_SHOPPING'"
    # After the source's nextAllowedAt:
    .\scripts\searchapi-smoke.ps1 -BaseUrl http://localhost:18087
    docker exec tech-advisor-postgres psql -U techadvisor -d techadvisor_searchapi_demo -c "SELECT count(DISTINCT d.id) AS documents,count(c.id) AS chunks FROM review_documents d LEFT JOIN review_chunks c ON c.review_document_id=d.id WHERE d.provider='SEARCHAPI_GOOGLE_SHOPPING'"
    ```

    Identical reviews keep both counts unchanged and avoid embedding. Real newly
    added/edited reviews may legitimately increase counts. A changed relative date
    alone never changes identity. Live reruns consume two more searches when cached.

15. Stop the local backend with Ctrl+C when finished. Keep scheduling disabled;
    clear the source opt-in when returning to ordinary development. Restore your
    normal root `.env` database selection when you want to use your regular database.

## Failure behavior and remaining limits

- No match/ambiguity/empty catalogue: sanitized reason in run errors, no corpus writes.
- Empty or entirely filtered reviews: successful zero-payload run, no fabricated evidence.
- Auth/malformed payload/HTTP errors/timeout: visible source failure, existing corpus intact.
- 429/503: existing bounded retry/cooldown handling. Do not retry manually in a loop.
- Embedding unavailable or bad space/dimension: visible failure, no partial batch.
- DB failure: transaction rollback. Existing unrelated evidence stays intact.
- Matcher suffix vocabulary is deliberately limited, not a general product resolver.
- Stable Google review IDs/URLs are not assumed. Edited title/text/rating becomes
  new evidence; old versions are retained. Dedupe does not merge different retailers.
- Short reviews, unknown domains and unusually long text can be missed. Long accepted
  reviews remain one chunk; the existing downstream prompt cap still applies.
- Untargeted lowest-ID selection can starve later products; the named manual UI
  selects a specific phone. Automatic refresh selection and scheduling
  policy are explicitly future work; the existing generic runner schedule is unchanged.
- Unknown publication dates cannot establish evidence maturity. No maturity-gate or
  recommendation changes are made here.
- First model loading and external latency still must fit the runner budget; keep
  the model baked and the AI service healthy. Failure remains retryable/idempotent.
- Human product-mapping overrides, aggregate ratings and a sentiment subsystem are
  outside this ticket.

## Verification evidence (24 September 2026)

- Backend `mvnw verify`: 94 tests, 0 failures/errors, 1 skipped optional real-AI
  smoke fixture; 93 passed and application packaging succeeded.
- Optional `ReviewPersistenceTests#realEmbeddingSmokeFixture`: separately executed
  successfully against a local FastAPI process and real baked Model2Vec. SearchAPI
  alone was mocked. First run committed two documents/chunks; repeat returned DUPLICATE.
- Real `scripts.verify_searchapi` returned the newly ingested battery review first
  for `battery life` (test product 37, chunk 9), camera second (chunk 8), and no leak
  into product 987654321. Test fixtures are local-only and cleaned after verification.
- AI pytest: 112 passed, 1 skipped (no private root `.env` mounted in the isolated
  test container). All eight pgvector integration tests ran with the real model.
- Fresh-database `mvnw flyway:migrate` validated/applied all seven migrations.
- Frontend unchanged; frontend tests/build were not run.
- Zero live SearchAPI requests and zero LLM calls. Live provider behavior remains
  an optional user-run check using the procedure above.

Backend tests cover matching, cache/recovery, normalization, dates, profile omission,
HTTP auth/localization/sanitized errors, batching, embedding contract checks,
atomic persistence/rollback, cancellation, idempotency, selection and existing corpus.
AI tests cover endpoint authentication, bounds, shared vectors, batch dispatch and
sanitized failures, alongside the existing retrieval/assessment suites.

For automated real-AI verification, set `REVIEW_SMOKE_AI_URL` and
`REVIEW_SMOKE_AI_TOKEN`, run the single optional Java test, read
`backend/target/searchapi-smoke-product.txt`, run the Python verifier for that ID,
then delete only the `SearchApiTest` fixture products from the `_test` database.
The fixture intentionally remains after that optional test so Python can inspect it.
Ordinary Java tests never require the AI service. AI DB tests require
`TEST_DATABASE_URL`; without it they explicitly skip. They reject a non-`_test` DB.

Official contracts checked during implementation:
[Google Shopping](https://www.searchapi.io/docs/google-shopping) and
[Google Product Reviews](https://www.searchapi.io/docs/google-product-reviews).

### Named-product admin UI and variant picker (24 September 2026)

The frontend now exposes the SearchAPI checkbox, a required smartphone name when
checked, a **Find matching products** action, validated radio-button choices, inline
server errors, and the canonical product in run history. The selected external ID is
saved in existing run JSONB; no migration is required. Product tokens remain on the
server. Tests verify safe candidate responses, exact selected-ID ingestion, stale or
missing selection rejection, durable idempotency, and selection reset when the entered
name changes. SearchAPI calls are mocked in automated tests; no paid API or LLM calls
are made. The AI service code is unchanged.
in this follow-up, so its previously recorded suite was not rerun.
