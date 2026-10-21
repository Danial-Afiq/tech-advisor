# TechAPI benchmark ingestion

`techapi-soc-benchmarks` enriches existing VERIFIED smartphone catalogue rows
with raw Geekbench single-core and multi-core observations. It never creates or
updates phone specifications; MobileAPI.dev remains the specification source.

## Source and meaning

The source reads TechAPI's released `main` branch:

- generated smartphone records under `site/public/v1/smartphones/...` provide
  the phone-to-SoC reference;
- raw records under `data/soc/...` provide `geekbench_single` and
  `geekbench_multi`.

The generated API's calculated performance ratings are deliberately not used.
TechAPI does not identify the Geekbench version for these raw fields, so the
stored benchmark identities explicitly say `version unspecified`. Values are
labelled `representative SoC`: they describe the processor and must not be
presented as measurements made on the specific handset.

TechAPI's data and static API are licensed under
[CC BY-SA 4.0](https://github.com/GetTechAPI/TechAPI/blob/main/data/LICENSE.md).
Stored `source` values retain the raw dataset path, representative-SoC wording
and licence attribution. Any redistributed derived benchmark dataset must keep
the required attribution and share-alike terms.

## Matching and bounded coverage

The source reads each catalogue phone's brand, model and chipset. It builds a
TechAPI model slug, validates the returned brand/model, and requires the local
chipset to equal the referenced TechAPI SoC after conservative normalization.
It never uses substring matching. Missing, unknown, ambiguous, unverified or
inconsistent records are skipped and logged without failing other phones.

A bounded Git tree listing locates raw SoC paths without downloading TechAPI's
93,000-record smartphone index. All network reads use `SourceContext`, including
its HTTPS-only policy, ten-request ceiling, one-second spacing, one-minute source
deadline, retry policy and one-MiB response limit. SoC responses are cached for
the current run, so phones sharing a processor reuse one fetch.

`TECHAPI_MAX_PRODUCTS_PER_RUN` controls how many catalogue rows may be considered
(default 20, valid 1-100). The HTTP request ceiling may stop a run earlier. The
last completed product ID is persisted in `INGESTION_COORDINATOR` metadata, so
the next run resumes there. A completed pass resets the cursor to begin a future
refresh cycle.

## Storage and comparison

The existing `benchmark_results` table remains unchanged. `observed_at` is
TechAPI's `updated_at` dataset timestamp, interpreted as UTC when the source omits
an offset. Retrieval time is used only when that value is absent or malformed;
neither timestamp claims to be the time the benchmark was measured.

The sink returns `DUPLICATE` when the latest score, unit, direction and provenance
are unchanged. A changed value appends a new row, preserving history.
`BenchmarkResultRepository.findLatestPerBenchmark` selects the current row.
`SpecComparisonService` consumes it through the existing recommendation flow and
compares only equal benchmark names, units and `higher_is_better` directions.
No recommendation weights or thresholds are changed.

## Configuration and schedule

The source is disabled until explicitly listed:

```properties
INGESTION_ENABLED_SOURCES=techapi-soc-benchmarks
TECHAPI_GITHUB_TOKEN=
TECHAPI_MAX_PRODUCTS_PER_RUN=20
```

`TECHAPI_GITHUB_TOKEN` is optional; it raises GitHub API rate limits and stays on
the server. Never commit it. The source declares a 14-day interval. The existing
coordinator still wakes on its shared two-day schedule for other sources and
omits TechAPI until its persisted source-specific due time. Only a successful
scheduled TechAPI execution advances that due time. Manual, failed, skipped and
interrupted executions do not. No production scheduling or Fly.io setting is
enabled by this feature.

## Manual local verification (PowerShell)

1. Copy `.env.example` to `.env` if needed, fill the normal database/JWT/admin
   values, and set:

   ```properties
   INGESTION_SCHEDULING_ENABLED=false
   INGESTION_ENABLED_SOURCES=techapi-soc-benchmarks
   ```

2. Start PostgreSQL and the backend from the repository root:

   ```powershell
   docker compose up -d postgres
   Set-Location backend
   .\mvnw.cmd spring-boot:run
   ```

3. In another PowerShell terminal, log in as the configured administrator and
   start a manual run:

   ```powershell
   $login = Invoke-RestMethod -Method Post `
     -Uri 'http://localhost:8080/api/auth/login' `
     -ContentType 'application/json' `
     -Body (@{ email = 'YOUR_ADMIN_EMAIL'; password = 'YOUR_ADMIN_PASSWORD' } | ConvertTo-Json)

   $headers = @{
     Authorization = "Bearer $($login.token)"
     'Idempotency-Key' = "techapi-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
   }
   $body = @{
     sources = @('techapi-soc-benchmarks')
     reason = 'Local SCRUM-124 verification'
   } | ConvertTo-Json

   $run = Invoke-RestMethod -Method Post `
     -Uri 'http://localhost:8080/api/admin/ingestion/runs' `
     -Headers $headers -ContentType 'application/json' -Body $body
   $runId = $run.runId
   do {
     Start-Sleep -Seconds 1
     $result = Invoke-RestMethod -Uri "http://localhost:8080/api/admin/ingestion/runs/$runId" `
       -Headers @{ Authorization = "Bearer $($login.token)" }
   } until ($result.finishedAt)
   $result
   ```

4. Verify the stored values and history:

   ```powershell
   docker exec tech-advisor-postgres psql -U techadvisor -d techadvisor -c @"
   SELECT p.brand, p.model_name, ph.chipset,
          b.benchmark_name, b.score, b.unit, b.higher_is_better,
          b.source, b.observed_at
   FROM benchmark_results b
   JOIN products p ON p.id = b.product_id
   JOIN phone ph ON ph.product_id = p.id
   WHERE b.source LIKE 'TechAPI GitHub main%'
   ORDER BY p.id, b.benchmark_name, b.observed_at DESC, b.id DESC;
   "@
   ```

5. Run the same ingestion again. Unchanged rows should be reported as duplicates
   and the SQL row count should not increase. The coordinator metadata shows the
   resumable cursor and the scheduled due time after a successful scheduled run:

   ```powershell
   docker exec tech-advisor-postgres psql -U techadvisor -d techadvisor -c @"
   SELECT metadata->'sourceCursors'->>'techapi-soc-benchmarks' AS cursor,
          metadata->'nextSourceDue'->>'techapi-soc-benchmarks' AS next_due
   FROM system_log
   WHERE component = 'INGESTION_COORDINATOR';
   "@
   ```

If no rows are written, inspect the per-source run result and backend logs. The
normal causes are no eligible catalogue phones, a model slug absent from TechAPI,
an unverified/mismatched chipset, unavailable raw Geekbench fields, provider
failure, or exhaustion of the bounded request budget. No score is fabricated.
