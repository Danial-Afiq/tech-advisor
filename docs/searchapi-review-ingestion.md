# SearchAPI customer-review ingestion

This source attaches owner reviews to canonical `VERIFIED` smartphones. MobileAPI
(ticket 1.2) is the sole source of truth for `products`/`phone` rows — this source
never creates one. A named admin run resolves the typed name against the existing
catalogue (fuzzy-matched, not exact-string — see `ProductMatcher.matchCatalogue`)
and fails closed if nothing eligible matches, rather than inventing a catalogue
entry from an unverified admin-typed name. It does not score upgrades, select
recommendation candidates, or call an LLM.

## How the flow works

```text
Admin run
  -> SearchApiSource
  -> product-token cache or Google Shopping discovery
  -> Google Product Reviews: most_relevant + most_recent
  -> ReviewNormalizer
  -> ReviewBatchSink
  -> FastAPI POST /internal/embed
  -> review_documents + review_chunks
```

The adapter uses the shared ingestion runner, request limits, run history, and
idempotency rules described in [ingestion.md](ingestion.md). One product batch is
one runner payload. Existing fingerprints are removed before embedding. All
embedding work finishes before the database transaction starts; an embedding
failure writes nothing, and a database failure rolls back the whole batch.

Flyway V7 adds `external_product_mapping` and external identity/provenance fields
to `review_documents`. Do not rewrite V7 or earlier migrations. Existing review
documents and the 512-dimensional pgvector schema remain compatible.

Each accepted review becomes one `review_documents` row and one index-0
`review_chunks` row. The document stores its canonical product ID, provider, title,
fingerprint, ingestion time, and allowlisted metadata: source domain, rating, raw
date, and retrieval time. Reviewer names, avatars, profiles, and raw provider JSON
are discarded. `published_at` stays NULL when SearchAPI only provides a relative
date. No stable review URL is assumed.

## Product matching and provider requests

The source ID is `searchapi-google-product-reviews`; the stored provider value is
`SEARCHAPI_GOOGLE_SHOPPING`. SearchAPI is called with Bearer authorization and the
configured `gl`, `hl`, and `location` values. The defaults are Singapore, English,
and `sg`.

The matcher requires the canonical brand and ordered, contiguous model tokens.
It rejects accessories, refurbished or used listings, conflicting models, repeated
core tokens, and unknown suffixes. Untargeted ingestion chooses the valid identity
with the fewest extra variant words and rejects equally specific distinct matches.

The admin picker returns at most 20 valid titles and external product IDs. Product
tokens never reach the browser. The selected ID is stored in run metadata, included
in idempotency checks, and revalidated by the worker before its token is cached.
A missing or stale selection fails closed. A name with no eligible catalogue match
fails closed too — ingest the device via MobileAPI first; this source never creates
a `products`/`phone` row. An existing ineligible catalogue row is never promoted.

Mappings are scoped by product, provider, canonical name, `gl`, `hl`, and location.
A canonical-name or locale change misses the cache. Only a clear HTTP 400 saying a
cached `product_token` is invalid or expired causes invalidation and one rediscovery.
A newly discovered invalid token fails without another loop. There is no token TTL.

Request use per product is fixed:

- cached token: two review searches;
- uncached token: one Shopping discovery plus two review searches;
- named admin flow: one additional Shopping preview search.

There is no pagination. Invalid-token recovery may add the single rediscovery within
the shared cap. Do not add preview, validation, or pagination calls without reviewing
SearchAPI quota impact.

`SourceContext` keeps the existing 60-second source budget, ten-attempt cap,
one-second pacing, five-second connect timeout, 20-second request timeout, 1 MiB
response limit, and bounded 429/503 retry handling. SearchAPI has no local application
cooldown; a provider `Retry-After` deadline is still persisted and enforced.

## Review normalization, deduplication, and embeddings

Review text uses Unicode NFKC normalization, collapsed whitespace, and removal of
the prompt boundary strings. Ratings must be numeric from 1 to 5. The normalizer
rejects blank or under-20-character text, narrowly identifiable logistics-only
remarks, invalid domains or ratings, text over 8000 characters, titles over 500
characters, and date strings over 100 characters.

At most 100 unique reviews are retained from the two provider result sets. The
fingerprint is SHA-256 over length-prefixed normalized values for product ID, source
domain, title, text, and decimal rating. Dates, retrieval time, and reviewer data are
excluded. A database unique constraint also protects concurrent or repeated runs.

FastAPI's authenticated `POST /internal/embed` accepts 1-100 nonblank texts of at
most 8000 characters. It returns the configured embedder name, dimension 512, and
one vector per text. Spring rejects a different model name, dimension, vector count,
non-finite component, or all-zero vector. The endpoint uses the retrieval embedder,
does not initialize an LLM, and never writes to the database.

## Configuration

Copy `.env.example` to the repository-root `.env` and set real secrets only there.
The relevant values are:

```dotenv
SEARCHAPI_API_KEY=your-private-key
SEARCHAPI_GL=sg
SEARCHAPI_HL=en
SEARCHAPI_LOCATION=Singapore
SEARCHAPI_MAX_PRODUCTS_PER_RUN=1

INGESTION_ENABLED_SOURCES=searchapi-google-product-reviews
INGESTION_SCHEDULING_ENABLED=false

ADMIN_EMAIL=admin@example.com
ADMIN_PASSWORD=replace-with-a-private-password
JWT_SECRET=replace-with-at-least-32-bytes-of-random-secret

AI_SERVICE_URL=http://localhost:8000
AI_SERVICE_TOKEN=shared-private-token
AI_INGESTION_EMBEDDER=minishlab/potion-retrieval-32M
VECTOR_STORE=pgvector
EMBEDDER=model2vec

VITE_API_BASE_URL=http://localhost:18087
```

`SEARCHAPI_MAX_PRODUCTS_PER_RUN` defaults to 1 and accepts at most 3 — the real
ceiling under `SourceContext`'s 10-request-per-run budget, since each uncached
product costs 3 requests (1 discovery + 2 review searches). Enabling the
source without `SEARCHAPI_API_KEY` fails startup. `ADMIN_PASSWORD` must be at
least 12 characters, `AI_SERVICE_TOKEN` must match between Spring and FastAPI,
and the JWT secret must meet the normal backend requirements. Restart services
after `.env` changes.

## Local run

Use a separate database ending in `_demo`. The commands below assume the local
credentials from `.env.example` and its host port 5434; substitute your configured
values if they differ.

```powershell
docker compose up -d postgres
@'
SELECT 'CREATE DATABASE techadvisor_searchapi_demo'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname='techadvisor_searchapi_demo')
\gexec
'@ | docker exec -i tech-advisor-postgres psql -U techadvisor -d postgres -v ON_ERROR_STOP=1

cd backend
.\mvnw.cmd flyway:migrate '-Dflyway.url=jdbc:postgresql://localhost:5434/techadvisor_searchapi_demo' '-Dflyway.user=techadvisor' '-Dflyway.password=devpassword'
cd ..
docker compose up -d --build ai
Invoke-RestMethod http://localhost:8000/health
```

Start Spring Boot in another terminal:

```powershell
cd D:\SMU\Y2Sem1\CS203\Project\tech-advisor\backend
$env:SPRING_PROFILES_ACTIVE = 'ingestion-demo'
$env:SERVER_PORT = '18087'
.\mvnw.cmd spring-boot:run
```

Then start the frontend with `npm run dev`. On this branch the legacy frontend
URL is `http://localhost:5173/IngestionAdmin`; the routing/auth-flow branch will
make `/admin/ingestion` canonical. With an ADMIN session already stored through
the shared session abstraction, follow this workflow:

1. Check **SearchAPI customer reviews**.
2. Enter the brand and full model name.
3. Select **Find matching products**.
4. Choose one exact SearchAPI product.
5. Select **Run now** and inspect the result/history.

The helper below instead starts an untargeted run against an existing eligible
catalogue phone and polls it to completion:

```powershell
.\scripts\searchapi-smoke.ps1 -BaseUrl http://localhost:18087
```

The helper prompts for the configured administrator credentials, obtains a JWT
from `POST /api/auth/admin/login`, and sends that bearer token with a fresh
idempotency key. It never reads or sends the SearchAPI key directly. A successful
nonempty batch reports one processed runner payload even when the batch contains
many reviews; query the review tables for the review count. An unchanged rerun
reports a duplicate batch and writes no rows.

## Verify stored reviews and vectors

```powershell
@'
SELECT d.id,d.product_id,d.source_name,d.title,d.published_at,d.ingested_at,
       d.external_fingerprint,d.metadata,c.chunk_index,c.chunk_text,c.embedder,
       vector_dims(c.embedding) AS dimensions
FROM review_documents d
JOIN review_chunks c ON c.review_document_id=d.id
WHERE d.provider='SEARCHAPI_GOOGLE_SHOPPING'
ORDER BY d.id;

SELECT product_id,provider,gl,hl,location,matched_title,status,matched_at,last_verified_at
FROM external_product_mapping;
'@ | docker exec -i tech-advisor-postgres psql -U techadvisor -d techadvisor_searchapi_demo
```

Expect dimension 512, index 0, the full embedding model ID, and only the allowlisted
metadata keys. The mapping query deliberately omits cached product tokens.

For a read-only semantic and product-isolation check, replace the product ID:

```powershell
docker compose exec ai python -m scripts.verify_searchapi --product-id 1 --query 'battery life' --other-product-id 987654321
```

This script calls neither SearchAPI nor an LLM. It verifies that SearchAPI chunks for
the chosen product can be retrieved and do not appear in another product's results.
It complements `searchapi-smoke.ps1`, which checks the HTTP/admin ingestion workflow.

## Failures and troubleshooting

- **Source disabled:** add the source ID to `INGESTION_ENABLED_SOURCES` and restart.
- **No matching smartphone:** use brand plus the full model and choose a returned
  valid identity; accessories and conflicting variants are deliberately rejected.
- **Empty successful run:** SearchAPI returned no reviews that passed normalization.
- **401/403:** check `SEARCHAPI_API_KEY`; provider messages and credentials are not
  copied into run history.
- **429/503 or deferred run:** wait until the provider `Retry-After` deadline.
- **Embedding failure or model mismatch:** confirm FastAPI health, token, model ID,
  and 512-dimensional configuration. No partial review batch is written.
- **Flyway checksum mismatch:** use a fresh demo database; never edit an old migration.
- **Database failure:** the review batch rolls back and existing evidence remains.

The matcher is intentionally limited rather than a general catalogue resolver.
Stable Google review IDs and publication dates are unavailable, edited review content
becomes new evidence, and long accepted reviews remain a single chunk. Broad automatic
new-product discovery, evidence maturity gates, mapping overrides, aggregate ratings,
and scheduled catalogue refresh remain future work.

## Automated verification

Automated tests mock SearchAPI and make no live LLM calls:

```powershell
cd backend
.\mvnw.cmd verify
cd ..\frontend
npm test -- --run
npm run build
cd ..\ai
.\.venv\Scripts\python.exe -m pytest
```

The optional real-embedding Java smoke test is gated by `REVIEW_SMOKE_AI_URL` and
`REVIEW_SMOKE_AI_TOKEN`. AI database tests require `TEST_DATABASE_URL` pointing to a
database whose name ends in `_test`. Live SearchAPI verification remains a deliberate
manual action because it consumes paid provider requests.

Provider contract references:
[Google Shopping](https://www.searchapi.io/docs/google-shopping) and
[Google Product Reviews](https://www.searchapi.io/docs/google-product-reviews).
