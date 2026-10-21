# TechAPI benchmark ingestion

`techapi-soc-benchmarks` enriches existing VERIFIED smartphones with TechAPI's
raw Geekbench 6 single-core and multi-core scores. It does not create products,
change phone specifications, refresh existing scores, or alter recommendation
weights.

## Source and stored meaning

The adapter reads the released TechAPI `main` Git tree. Raw files under
`data/soc/...` provide `geekbench_single` and `geekbench_multi`; generated
smartphone records are used only when a newly added local phone must be mapped
to its processor. The calculated performance ratings in TechAPI's generated API
are not used.

Scores are stored in the existing `benchmark_results` table as:

- `Geekbench 6 CPU Single-Core`
- `Geekbench 6 CPU Multi-Core`

They are representative processor-level observations, not handset test runs.
The `source` value preserves the raw TechAPI path, that processor-level meaning,
and CC BY-SA 4.0 attribution. `observed_at` uses TechAPI's `updated_at` when
available and retrieval time otherwise.

## Phase 1: initial backfill

The first run pins the current `data/soc` Git tree SHA and the highest eligible
local product ID. It then walks every SoC JSON path in stable path order. For
each verified record with a positive raw score, the processor is matched to
eligible local phone chipsets using exact conservative normalized identities.
All confidently matching products receive the score.

Each manual run handles at most `TECHAPI_MAX_PRODUCTS_PER_RUN` source records.
Despite the retained environment-variable name, during the initial phase this
is a source-record batch size. The last completed path, processed count, total,
catalogue snapshot and tree SHA are saved in the existing `system_log` table
under `TECHAPI_BENCHMARK_STATE`. A failed or interrupted item is not
checkpointed, so the next run retries it. Completion never resets the cursor.

During the initial backfill, the admin ingestion page shows `processed / total`
progress, observations stored and products covered. Select **TechAPI processor
benchmarks** and choose **Run now** repeatedly until the initial-backfill card
disappears, which means the source has entered incremental monitoring.

## Phase 2: incremental coverage

After backfill, successful completed scheduled checks are due every 14 days.
The adapter first compares the saved SoC tree SHA with the current one. If it is
unchanged, no SoC files are downloaded. If it changed, immutable old and new Git
trees are compared by path and blob SHA. Only these records are fetched:

- new TechAPI processors with scores;
- existing processor records whose blob changed, allowing a previously absent
  single-core or multi-core score to be discovered; and
- newly added local phones after the saved catalogue product ID.

An existing TechAPI metric for a product is immutable for this feature. Numeric
changes to a score already stored are returned as duplicates and do not append
history. A changed processor file can still add the other, previously missing
metric. New local phones are checked against the current pinned SoC tree and
then checkpointed by product ID.

Large incremental changes use the same bounded batch and durable source/tree
and catalogue checkpoints. An incomplete scheduled batch remains due at the
next shared scheduler wake-up; only a completed incremental cycle starts the
next 14-day wait. Manual runs continue immediately without changing schedule
dates.

## Safety and matching

All requests go through `SourceContext`, preserving HTTPS-only access, one-second
pacing, retries, cancellation, response-size limits and provider `Retry-After`
deferrals. The per-run request limit is `4 + (3 * batch size)` and the time
budget is that many seconds plus 30 seconds. The valid batch range is 1–100.
The adapter downloads bounded Git metadata and individual JSON blobs; it never
clones or downloads the full repository.

Missing, non-positive, unverified, malformed, ambiguous or non-matching records
are skipped without fabricating a value. Matching never uses a loose substring.
There is no application cooldown, so consecutive manual batches are allowed.

## Configuration

```properties
INGESTION_ENABLED_SOURCES=techapi-soc-benchmarks
TECHAPI_GITHUB_TOKEN=
TECHAPI_MAX_PRODUCTS_PER_RUN=20
```

`TECHAPI_GITHUB_TOKEN` is optional and only raises GitHub API rate limits. Never
commit it. `INGESTION_SCHEDULING_ENABLED` controls the existing shared scheduler;
this feature does not enable production scheduling automatically.

## Manual local verification

Start PostgreSQL, the backend and frontend as described in the README. Sign in
as an ADMIN and open `/admin/ingestion`. Select **TechAPI processor benchmarks**
and run batches. The progress card should increase and survive backend restarts.

Inspect the durable checkpoint:

```powershell
docker exec tech-advisor-postgres psql -U techadvisor -d techadvisor -c @"
SELECT status,
       metadata->>'phase' AS phase,
       metadata->>'completedUnits' AS processed,
       metadata->>'totalUnits' AS total,
       metadata->>'cursor' AS last_source_path,
       metadata->>'initialBackfillComplete' AS complete,
       metadata->>'catalogueCursor' AS catalogue_cursor,
       metadata->>'baselineRevision' AS source_revision
FROM system_log
WHERE component = 'TECHAPI_BENCHMARK_STATE'
ORDER BY id DESC
LIMIT 1;
"@
```

Inspect stored coverage:

```powershell
docker exec tech-advisor-postgres psql -U techadvisor -d techadvisor -c @"
SELECT p.brand, p.model_name, ph.chipset,
       b.benchmark_name, b.score, b.unit, b.higher_is_better,
       b.source, b.observed_at
FROM benchmark_results b
JOIN products p ON p.id = b.product_id
JOIN phone ph ON ph.product_id = p.id
WHERE b.source LIKE 'TechAPI GitHub main%'
ORDER BY p.id, b.benchmark_name, b.id;
"@
```

After the initial phase is complete, another unchanged run should finish with
zero new observations and leave the row count unchanged. Adding a new VERIFIED
phone with a matching chipset makes it eligible on the next run. Provider
failures leave the last successful source path or product ID intact for retry.
