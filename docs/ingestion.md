# Ingestion runner: operation and source integration

The runner provides a 14-day schedule, asynchronous admin requests, source isolation and persistent run history. It includes simulated adapters and the opt-in SearchAPI owner-review source and RAG sink. See [SearchAPI review ingestion](searchapi-review-ingestion.md) for configuration and a complete local live test.

## Shared contract, different payloads

```text
UTC schedule / admin request
             |
      durable run admission
             |
       source registry
             |
  source adapter -> Payload envelope -> typed IngestionSink
             |
      progress in system_log
```

Every `IngestionSource` identifies itself and implements `ingest(context, output)`. An adapter collects and translates data; it does not schedule itself, start background threads, or update run logs. All emitted items have a source ID, stable external ID, observation time, and a typed body:

| Body | Data | Downstream responsibility |
| --- | --- | --- |
| `Article` | Title, URL, published time, text, optional product reference | Review/news persistence, product matching, later RAG/sentiment analysis |
| `Specifications` | Product reference, numeric measurements and explicit units | Typed catalogue updates and domain-specific range validation |
| `Price` | Product reference, amount, ISO-style currency code | Price history and change detection |
| `Benchmark` | Product reference, benchmark name, score, unit | Benchmark storage/comparison |
| `ReviewBatch` | Canonical product ID and up to 100 normalized reviews | Batch embeddings through FastAPI, then atomic review document/chunk persistence |

An RSS article is not forced into specification fields. Sources offering the same type translate to the same body. A source can emit several types. Review collection preserves evidence; sentiment inference belongs downstream. Product resolution and type-specific domain validation remain the sink's responsibility.

## Adding an RSS source

For a feed such as Hackfeed, implement an adapter to fetch the configured feed and translate entries into `Article` bodies. Verify the actual feed endpoint and permission to use it when adding that source; no endpoint is assumed here.

This compilable adapter skeleton shows the integration boundary. Supply a secure RSS parser and an article sink as separate Spring beans:

```java
package com.springboot.backend.ingestion;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HackfeedSource implements IngestionSource {
    private final URI feed;
    private final FeedParser parser;
    public HackfeedSource(@Value("sources.hackfeed.url") URI feed, FeedParser parser) {
        this.feed = feed;
        this.parser = parser;
    }
    public String sourceId() { return "hackfeed-rss"; }
    public void ingest(SourceContext context, Consumer<Payload> output) throws Exception {
        for (Entry entry : parser.parse(context.get(feed))) {
            context.check();
            output.accept(new Payload(sourceId(), entry.id(), context.now(),
                new Payload.Article(null, entry.title(), entry.url(), entry.publishedAt(), entry.text())));
        }
    }
    public interface FeedParser { List<Entry> parse(byte[] xml); }
    public record Entry(String id, String title, URI url, Instant publishedAt, String text) {}
}
```

Use the RSS GUID as external identity, with a documented canonical-URL fallback if missing. Disable DTDs/external entities in XML parsing. Keep parsing bounded. Do not fetch article URLs merely because they appear in feed data; any expansion needs its own validated host policy. `context.get` accepts HTTPS only and does not follow redirects. URLs come from trusted server configuration, not admin request bodies.

Implement an `IngestionSink` with `supports(source, body)` for article data. Its `accept(runId, payload)` must durably store/upsert before returning `ACCEPTED`, or return `DUPLICATE` when the observation is already stored. Exactly one sink must match each emitted payload; a missing/ambiguous sink fails that source visibly. Never install a production sink that silently discards data. A price observation's identity may need timestamp/version information to preserve genuinely new observations while deduplicating retries.

Then add `hackfeed-rss` to `INGESTION_ENABLED_SOURCES`, configure its feed URL, and test a fixture for valid entries, malformed data, stable identity, duplicate handling, and source failure. No scheduler/controller changes are required. Startup rejects duplicate IDs and unknown enabled sources. Adding a completely new data category requires a new `Payload.Body` type, validation and sink, but adding another source for an existing category does not.

## Load and failure policy

- One global pipeline claim; sources run sequentially. Admin/scheduled requests share cooldowns.
- Each source has a 60-second execution budget, 1,000 emitted-item limit, at most 10 HTTP attempts, at least one second between requests, a five-second connect timeout and 20-second request timeout. HTTP response bodies are capped at 1 MiB.
- HTTP 429/503 receive at most two retries. Long Retry-After values defer the source in persistent coordinator state instead of sleeping indefinitely. A completed source uses its configured cooldown (15 minutes by default); transport/timeouts use one minute; validation/no-match failures use none. Other failures retain the configured cooldown. Demo sources use zero cooldown.
- Adapters must use `SourceContext.get` and call `check()` while processing. On cancellation or ownership loss, stop. The HTTP helper closes responses and cancels its client when the source budget expires. The source executor has no backlog and only one thread, limiting damage from an adapter ignoring interruption.
- Validation failures increment rejected/error counters and allow later items to proceed. Source exceptions produce bounded sanitized application stack frames; messages, raw response bodies and credentials are excluded. Later sources still execute.
- Duplicate detection in the runner covers repeated IDs of the same type within one source run. Cross-run deduplication belongs in the durable typed sink. Demo receipts intentionally persist again on each new demo run.

Limits currently live in `SourceContext` and `IngestionOrchestrator`; adapt them deliberately with tests when a real source needs a different policy. A Java process cannot forcibly stop arbitrary code that ignores interruption, and it cannot promise exactly-once external effects during a crash. Do not implement adapters with independent executors or irreversible external actions.

SearchAPI's sink accepts one product batch per payload. Run counters count batches;
query the review tables for review counts. Its context-aware sink checks ownership
around the embedding call and before commit. SourceContext authenticated GETs retain
all existing limits and validate the exact trusted credential destination host.

## Scheduling and recovery

The schedule was temporarily set to daily on 2026-09-16 (Flyway V3) specifically to make the scheduler observable within a short testing window; that was never the target production cadence. As of 2026-09-22 it is reverted to the real intended cadence: every 14 days, via the `RunStore.INTERVAL` constant rather than a further data migration (no production data depended on the daily anchor). The first run is **17 September 2026 at 13:00 SGT (05:00 UTC)**, then every 14 days from that anchor.

For the local scheduled run, keep the backend, Docker and computer running and awake. Closing the browser is fine. If the backend is offline at the due time, the catch-up runs after recovery. Scheduled results are available at `GET /api/admin/ingestion/runs?trigger=SCHEDULED`. The enabled simulated failure fixture intentionally makes the combined demo run report `PARTIAL_FAILURE` with 3 accepted payloads and 1 exception stack.

Set `INGESTION_SCHEDULING_ENABLED=true`, `INGESTION_ANCHOR` to the first due UTC instant (for example `2026-09-17T05:00:00Z`), and an enabled-source list. Scheduling is disabled in the default production profile and enabled in `ingestion-demo`, unless explicitly overridden by `INGESTION_SCHEDULING_ENABLED=false`. The persisted anchor is authoritative after initialization; changing the environment does not silently reset an existing schedule. A deliberate schedule change needs a reviewed migration of coordinator state.

The next slot is always previous scheduled slot +14 days, independent of duration or manual runs. Spring TaskScheduler arms that instant. A ten-second reconciliation sweep repairs dispatch after restarts; it does not scrape every ten seconds. Concurrent replicas serialize admission through a short PostgreSQL row lock.

If the backend was offline, one run represents the most recent overdue slot; `missedSlots` records older coalesced slots. A due slot waits while another run is active. Run metadata captures actual start and lateness. One durable admission per due slot is guaranteed; exact execution at that instant requires a healthy running process and database.

Accepted work is durable before dispatch. If dispatch never occurs, the next sweep can claim it. Running work has a unique owner, ten-second heartbeat and 90-second lease. Expired running work becomes `INTERRUPTED` and is not blindly replayed. All progress/finalization writes check owner and lease. The schedule retains its cadence after interruption; an admin may initiate a new run when appropriate.

`backend/fly.toml` disables automatic stopping and starting so deployed Machines keep running without HTTP traffic. This incurs ongoing runtime cost once deployed. No deployment was performed by this change. Keep machine clocks synchronized; due times/leases use UTC application clocks. Retain coordinator and active-run rows if adding log retention later.

## Database and metadata

Flyway V2 creates `system_log`. The reserved `INGESTION_COORDINATOR` row stores scheduling, active ownership and source cooldowns. `INGESTION_RUN` rows store execution history. `INGESTION_DEMO_PAYLOAD` rows are simulation receipts only. V6 owns the review corpus; V7 adds external product mappings and review identity/provenance for SearchAPI.

```sql
SELECT id, status, created_at,
       metadata->>'runId' AS run_id,
       metadata->>'startedAt' AS started_at,
       metadata->>'finishedAt' AS finished_at,
       metadata->>'durationMs' AS duration_ms,
       metadata->>'processedPayloadCount' AS processed,
       metadata->>'errorStackCount' AS exception_stacks
FROM system_log
WHERE component = 'INGESTION_RUN'
ORDER BY created_at DESC;
```

`processedPayloadCount` counts sink-accepted items; duplicates and rejected payloads have separate counters. `errorCount` includes validation failures and exceptions; `errorStackCount` counts captured exception stacks, not frames. Source results contain their own counters/timestamps. `skippedSourceCount` counts cooldown skips. Overall states: `ACCEPTED`, `RUNNING`, `SUCCESS`, `PARTIAL_FAILURE`, `FAILED`, `INTERRUPTED`, `SKIPPED` (all sources in cooldown).

## Admin access and UI

For a named SearchAPI import, check **SearchAPI customer reviews**, enter the brand
and full model name (for example **Apple iPhone 16 Pro**), then click **Run now**.
An existing eligible phone is selected directly. For an unknown name, the asynchronous
source requires one unambiguous matching SearchAPI identity before transactionally
creating the VERIFIED `products` row, `phone` row and external mapping. A no-match or
ambiguous result creates nothing. Known ineligible catalogue rows remain rejected.
The Product column records the requested or resolved canonical name.

The existing run request accepts optional `productName`; omit it to keep source-default
selection. When supplied it requires SearchAPI among the enabled selected sources.
Resolved product ID/name, or the new-product discovery name with a null ID, are durable
run metadata and part of idempotency checking.
Changing the product with the same idempotency key returns 409. SearchAPI validates
the product again before fetching, so deleted, renamed or unverified products fail
instead of silently falling back to another phone. Existing source cooldowns still apply.

The panel is at `/admin/ingestion`. It displays source choices, optional reason, next scheduled time and recent results. Requests are asynchronous (`202` plus a Location header). The browser polls results, handles conflicts and retains an idempotency key for retrying a failed submission with the same body. Server admission also blocks overlapping requests, including from different browser tabs.

Production routes are deliberately denied until the account-auth ticket supplies the trusted ADMIN identity. Replace the scoped `closedIngestion` chain with the account integration, retain server-side role checks and appropriate CSRF protection, and rerun security tests. Do not enable `ingestion-demo` in production to bypass this dependency. The demo uses a localhost-bound HTTP Basic admin account with a required environment password and CSRF-protected writes. Credentials are held only in browser memory; do not put them in Vite configuration or localStorage. `VITE_INGESTION_DEMO=true` exposes the local demo login form, not a production authorization mechanism.

API contracts are in `docs/ingestion-openapi.yaml`. The endpoint group supports run submission, run detail/history, source availability, schedule state and an authenticated CSRF/session read. Idempotency keys are scoped to the actor, with differing payloads rejected. History is paginated using `page`/`size` and optional `status`/`trigger` filters.

## Local demo and verification

Create dedicated local databases alongside the existing Docker PostgreSQL instance:

```powershell
docker exec tech-advisor-postgres psql -U techadvisor -d postgres -c "CREATE DATABASE techadvisor_ingestion_test"
docker exec tech-advisor-postgres psql -U techadvisor -d postgres -c "CREATE DATABASE techadvisor_ingestion_demo"
$env:POSTGRES_DB = 'techadvisor_ingestion_test'
cd backend
.\mvnw.cmd verify
cd ..
.\scripts\ingestion-demo.ps1
```

Tests intentionally require a database name ending `_test`; they delete ingestion records only in that isolated test database. CI uses `techadvisor_test`. Set `JAVA_HOME` to your Java 21 JDK directory before running the demo script. It requires a database ending `_demo`, starts a backend on localhost:18087, runs success and partial-failure cases, verifies shutdown, restarts the backend, retrieves both persisted results, and writes `docs/examples/ingestion-demo-results.json`. It restores its environment and stops only its own backend process. It generates an ephemeral password when none is supplied. Re-running adds new demo history.

Verification on 2026-09-16: 14 backend tests passed, backend packaging passed, 3 frontend tests passed, and frontend build/lint passed. The recorded manual success run accepted 3 payloads in 240 ms with 0 exception stacks; the partial-failure run accepted 3 in 125 ms with 1 exception stack. Both were retrieved after an actual backend shutdown/restart and checked directly in PostgreSQL. Browser visual verification could not run because the installed browser runtime rejected its bootstrap dependency; the admin flow was covered by component tests and the backend API demo.

For interactive UI use, start the backend yourself with `POSTGRES_DB=techadvisor_ingestion_demo`, `SPRING_PROFILES_ACTIVE=ingestion-demo` and an `INGESTION_DEMO_PASSWORD` of at least 12 characters. In another terminal:

```powershell
cd frontend
$env:VITE_INGESTION_DEMO = 'true'
$env:VITE_API_BASE_URL = 'http://localhost:8080'
npm run dev
```

Open `http://localhost:5173/admin/ingestion`, enter the configured password, select sources and click Run now. The default source fixtures produce three accepted payloads; selecting the failure fixture as well gives one captured exception. Production scheduling stays disabled in this manual demo. Fake-clock PostgreSQL tests exercise the scheduling admission path without shortening the real 14-day interval.

## References

- [Spring task execution and scheduling](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)
- [Spring Security filter chain configuration](https://docs.spring.io/spring-security/reference/servlet/configuration/java.html)
- [Spring Security CSRF protection](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
- [Fly.io autostop/autostart](https://fly.io/docs/launch/autostop-autostart/)
