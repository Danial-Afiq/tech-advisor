# SCRUM-143 — Phase 0 name map

Read from `main` at `ad9d498` (branch `feat-SCRUM-143-end-to-end-test`, 12 Nov 2026).
Every row is the real identifier in code, with the file/line it was found at. Paths are
relative to `backend/src/main/java/com/springboot/backend/` (prefix `B/`), to
`backend/src/test/java/com/springboot/backend/` (`T/`), or to the repo root.

Status key: **OK** = found as expected · **DIFFERS** = found, but not as the plan assumed ·
**NOT FOUND** = does not exist in code.

## A. Re-confirmed (seen) names

| Plan name | Status | Where |
|---|---|---|
| `SimulatedRecommendationWorkflowIntegrationTest` | OK | `T/ingestion/simulation/SimulatedRecommendationWorkflowIntegrationTest.java` |
| `SimulatedMarketChangeService.injectPrice(PriceCommand)` | OK | `B/ingestion/simulation/SimulatedMarketChangeService.java:46`; `record PriceCommand(long productId, BigDecimal newPrice, String idempotencyKey)` at `:333`; `@Profile("ingestion-demo")` |
| `PRICE_CHANGE` | OK | `B/marketevent/MarketEventType.java:12` |
| `SimulatedHistoryDatabaseSafetyTest`, `SimulatedMarketChangeIntegrationTest`, `SimulatedMarketChangeTransactionTest` | OK | `T/ingestion/simulation/` |
| `AiAssessmentClient` (`assess(AssessRequest)`) | OK | `B/recommendation/AiAssessmentClient.java` |
| `RecommendationEmailSender` | OK | `B/email/RecommendationEmailSender.java` |
| `RecommendationTriggerConfiguration.EXECUTOR` | OK | `B/recommendation/trigger/RecommendationTriggerConfiguration.java:25` (`"recommendationExecutor"`) |
| `CandidatePruningService` | OK | `B/recommendation/CandidatePruningService.java` (`getViableCandidates(Long)`); query `ProductRepository.findPricedCandidates` at `B/repository/ProductRepository.java:114` |
| `CandidateFilterIntegrationTest` | OK | `T/recommendation/CandidateFilterIntegrationTest.java` |
| `AssessRequestFactoryTest` | OK | `T/recommendation/trigger/AssessRequestFactoryTest.java` |
| `TierMapper` | OK | `B/recommendation/classification/TierMapper.java` |
| `ShippedScoringSettings` (test fixture) | OK | `T/recommendation/classification/ShippedScoringSettings.java` (binds `application.properties`) |
| `UpgradeScoringServiceTest`, `PriorityScoringTest`, `TierMapperTest` | OK | `T/recommendation/classification/` |
| `AiAssessmentClientTests`, `AssessContractTests`, `FrankfurterClientTests` | OK | `T/recommendation/`, `T/currency/` |
| `NOT_FLEXIBLE` | OK | `B/recommendation/AssessVocabulary.java:31`; applied in `CandidatePruningService.getViableCandidates` → `ProductRepository.java:100` (`sameBrandOnly`) |
| `phone` specs table | OK | `V6__create_sprint_1_schema.sql` `CREATE TABLE phone` |
| `ai/app/prompt.py` | OK | see section C |

## B. Backend concepts (C1, C2, C3, C7, C9)

| # | Concept | Real name | Where | Status |
|---|---|---|---|---|
| C1 | Verdict column | `recommendations.verdict` (TEXT NOT NULL) | `V6__create_sprint_1_schema.sql`, `CREATE TABLE recommendations` | OK |
| C1 | Evidence grade column | `recommendations.confidence` (TEXT, `A`–`F` or `-`; **NULL on deterministic-only rows**) | V6; AGENTS.md §14.12 "Current semantic change to `confidence`" | DIFFERS (named `confidence`, not `evidence_grade`) |
| C1 | Input snapshot | `recommendations.input_snapshot` (JSONB NOT NULL DEFAULT `'{}'`) | V6; written by `RecommendationService.buildInputSnapshot` (`B/recommendation/RecommendationService.java`) | OK. **Default is `{}`, not NULL**, so "filled in" must assert non-empty keys |
| C1 | Factor breakdown | `recommendations.factor_analysis` (JSONB NOT NULL DEFAULT `'{}'`), keys `deterministic`, `evidence`, `irrelevant_chunk_ids` | V6; `RecommendationService.buildFactorAnalysis` | OK. Same `{}` note |
| C1 | Summary text | `recommendations.reasoning` (TEXT), filled from `AssessResponse.summary()` | V6; `RecommendationService.java:49` | DIFFERS (named `reasoning`) |
| C1 | Row lifecycle | `recommendations.status` (`ACTIVE` / superseded); the AI row **supersedes** the deterministic row for the same (user, device, candidate) | `B/recommendation/RecommendationRepository.java:57-63` (`supersedeAndInsert`) | Assertions must filter `status = 'ACTIVE'` |
| C2 | Verdict values, low → high | `NO_MEANINGFUL_CHANGE`, `WORTH_WATCHING`, `WORTH_CONSIDERING`, `STRONG_UPGRADE_CANDIDATE` | `TierMapper.java:19-22`; ordered list `AssessVocabulary.VERDICTS` (`:17`) | OK |
| C3 | Band thresholds (read only) | `recommendation.scoring.watching-threshold=0.50`, `considering-threshold=0.67`, `strong-threshold=0.83`, lower bound inclusive; `min-spec-coverage=0.34`; `priority-max=5`; `default-priority=1`; **`missing-priority=0`** | `backend/src/main/resources/application.properties:57-68`; record `ScoringSettings` | OK |
| C3 | Score formula | priority-weighted average of per-factor improvements, clamped to [0,1]. A factor absent from `priorities` gets weight **0** (`missing-priority=0`), so `{"battery":5}` scores on battery alone | `B/recommendation/classification/UpgradeScoringService.java` `score(...)` | Note for Phase 1 expectations |
| C3 | AI-call gate in Java | Only `NO_MEANINGFUL_CHANGE` skips the AI call | `RecommendationTriggerService.java:181` | OK |
| C3 | Java "missing context" skip | AI call skipped if condition / satisfaction / urgency / brand flexibility missing or not in the closed vocabulary | `B/recommendation/trigger/AssessRequestFactory.java:81-95` | Fixtures must fill all four |
| C7 | Camera fields | `phone.camera_specs` is **free TEXT only**. `camera` is in `PhoneSpecMapping` unscored list; `camera_specs` is a `TextSpecRule` (reported, never scored) | `B/recommendation/classification/PhoneSpecMapping.java` (`List.of(Factors.CAMERA, …)`) | **NOT FOUND as a numeric field.** Camera can't be compared; a camera priority has no scorable spec |
| — | Condition values | `EXCELLENT`, `GOOD`, `FAIR`, `POOR` | `AssessVocabulary.java:25` | OK (`EXCELLENT` exists) |
| — | Pain points | `device_preferences.pain_points` JSONB (string or list → joined prose) → `AssessRequest.Preferences.painPoints` | V6; `AssessRequestFactory.java:114`, `painPoints(...)` | OK |
| — | Satisfaction | `user_devices.satisfaction_score` INTEGER 0–100 | V6 | OK |

### How setup avoids triggering a recommendation

The inventory trigger (`B/recommendation/trigger/InventoryRecommendationTrigger.java`) listens for
`DeviceInventoryChanged`, which is published only by `B/service/DeviceService.java`. PR 87
inserts fixtures with `JdbcTemplate`, which never goes through `DeviceService`, so nothing fires
during setup. SCRUM-143 does the same. Scenarios then call the pipeline explicitly:

- `RecommendationTriggerService.runForDevice(long userDeviceId, String cause)`, at `RecommendationTriggerService.java:150`. Synchronous; evaluates the device's **whole shortlist**.
- `RecommendationTriggerService.runForMarketEvent(long, String)`, at `:83`. Only the event's product. This is what `injectPrice` drives (async, via `MarketEventRecommendationTrigger`).

**Risk for scenarios 1–4 and 6:** `runForDevice` shortlists **every** `VERIFIED` priced product
in the category (`ProductRepository.java:84-113`), not only the fixture's. If the `_test`
database contains other phones under budget, they become extra candidates and extra AI calls.
That breaks exact AI-call counts and pollutes `never()` in #3. Test-only mitigation, no
production change: scope verifications to the fixture candidate,
`verify(aiClient, never()).assess(argThat(r -> r.candidate().productId() == candidateId))`,
and count rows by candidate id. Alternatively, assert that no other priced `VERIFIED`
SMARTPHONE exists below the fixture budget before running.

**Brand-lock fixture (#3):** `sameBrandOnly` compares `LOWER(BTRIM(brand))`. The candidate
needs a different brand from the owned phone, so the fixture uses two brands, e.g.
`SCRUM143-E2E` and `SCRUM143-E2E-OTHER`. Cleanup must delete `brand LIKE 'SCRUM143-E2E%'`.

### C9: existing classification tests (avoid duplicates in Phase 4)

| Test | Already covers |
|---|---|
| `UpgradeScoringServiceTest` | identical → 0 / lowest tier; all-factor gain → top tier; small gain not top; regression clamp; equal weights with no priorities; coverage/insufficient data; caps; deciding factors; determinism; rounding |
| `PriorityScoringTest` | fixed priority fixtures with exact scores/verdicts; **top-priority gain beats low-priority specs, and opposite users get different verdicts** (overlaps #2/#6 in spirit); zero/missing/invalid/out-of-range priorities; budget headroom through `value` |
| `TierMapperTest` | every band boundary; email eligibility; misordered thresholds |
| `UpgradeClassificationIntegrationTest` | DB-backed flagship → STRONG; near-identical → NO_MEANINGFUL_CHANGE; spec overrides; breakdown auditability; **same candidate, opposite users → different verdicts** |
| `SpecComparisonServiceTest` | deltas, lighter-is-better, missing values, overrides, benchmarks, budget value, text specs never scored |

**Gap for Phase 4:** no test asserts that **condition / satisfaction leave the verdict unchanged**
(no matches for `condition`/`satisfaction` in `T/recommendation/classification/`). Scoring never
reads them, so a #4 unit test would lock in that decision. #2 and #6 are largely covered
already. Phase 4 should add only fixture-specific cases that differ.

## C. AI service and evidence (C4, C5, C6, C8)

### C6: review documents, chunks, embedding, factor tagging

| Concept | Real name | Where | Status |
|---|---|---|---|
| Review documents | `review_documents` (`id`, `product_id` FK, `source_name` NOT NULL, `source_url`, `title`, `published_at DATE`, `ingested_at`; V7 adds `provider`, `external_fingerprint`, `metadata`; unique `(product_id, provider, external_fingerprint)`) | `V6…sql:148-157`, `V7__add_external_review_ingestion.sql:18-23` | OK |
| Review chunks | `review_chunks` (`review_document_id`, `chunk_index`, `chunk_text`, `embedding vector(512)`, `embedder`); HNSW cosine index | `V6…sql:164-175`, `:222-227` | OK |
| **Factor tagging** | **No factor column, no tagger.** Retrieval scoping is purely semantic | `ai/app/retrieval/query.py:3`; AGENTS.md §0.1 marks factor-tagged chunks superseded | **NOT FOUND (by design)** |
| Chunking | None. **One review → one chunk** at `chunk_index` 0 | `B/ingestion/reviews/ReviewBatchSink.java:80-83` | DIFFERS |
| Real ingestion path | `SearchApiSource` → `Payload.ReviewBatch(long productId, List<Review>)` → `ReviewBatchSink.accept(runId, payload[, context])`. Embeds via `ReviewEmbeddingClient.embed(List<String>)` → Python `POST /internal/embed` (`ai/app/main.py:127`). Product must be `status='VERIFIED'` and in a configured category | `B/ingestion/searchapi/SearchApiSource.java:24,129`; `B/ingestion/core/Payload.java:14,17,78-95`; `ReviewBatchSink.java:37-83`; `B/ingestion/reviews/ReviewEmbeddingClient.java` | OK |
| **Sink writes `published_at = NULL`** and `source_name = "Google Shopping reviews via SearchAPI / <domain>"` | | `ReviewBatchSink.java:70-77` | **DIFFERS. See blocker B2** |
| Payload validation | fingerprint `[a-f0-9]{64}` (`ReviewNormalizer.fingerprint(...)`, `B/ingestion/searchapi/ReviewNormalizer.java:50`); text ≤ 8000; 1–100 reviews per batch; rating 1–5; non-null title and rawDate | `Payload.java:78-95` | OK |
| Existing review-seeding tests | `T/ingestion/reviews/ReviewPersistenceTests.java` (mocked embedder; `realEmbeddingSmokeFixture` with real AI when `REVIEW_SMOKE_AI_URL` is set); `T/ingestion/searchapi/SearchApiCategoryIntegrationTest.java:87`; `ai/tests/test_pgvector.py` | | OK |
| Demo profile reviews | `SimulatedMarketChangeService` only has `injectPrice` / `injectBenchmark`; it **cannot inject reviews** | `SimulatedMarketChangeService.java:46,92` | NOT FOUND (not needed if the sink is used) |
| Java-side maturity gate (AGENTS.md §8.3) | **Not implemented.** Java never checks review count/age before `/assess` | AGENTS.md:744; `AiAssessmentStep.java` | Confirms AGENTS.md |

### C4 / C5 / C8: AI service

_Pending: AI-service discovery in progress; filled in the next commit._
