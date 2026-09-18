# Ingestion runner: operation and source integration

The runner provides a 24-hour schedule, asynchronous admin requests, source isolation and persistent run history. This ticket includes simulated adapters and receipts, not live market sources or catalogue/RAG processors.

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
- Each source has a 60-second execution budget, 1,000 emitted-item limit, at most 10 HTTP attempts, at least one second between requests, a five-second connect timeout and ten-second request timeout. HTTP response bodies are capped at 1 MiB.
- HTTP 429/503 receive at most two retries. Long Retry-After values defer the source in persistent coordinator state instead of sleeping indefinitely. The source's default cooldown is 15 minutes; demo sources alone use zero cooldown.
- Adapters must use `SourceContext.get` and call `check()` while processing. On cancellation or ownership loss, stop. The HTTP helper closes responses and cancels its client when the source budget expires. The source executor has no backlog and only one thread, limiting damage from an adapter ignoring interruption.
- Validation failures increment rejected/error counters and allow later items to proceed. Source exceptions produce bounded sanitized application stack frames; messages, raw response bodies and credentials are excluded. Later sources still execute.
- Duplicate detection in the runner covers repeated IDs of the same type within one source run. Cross-run deduplication belongs in the durable typed sink. Demo receipts intentionally persist again on each new demo run.

Limits currently live in `SourceContext` and `IngestionOrchestrator`; adapt them deliberately with tests when a real source needs a different policy. A Java process cannot forcibly stop arbitrary code that ignores interruption, and it cannot promise exactly-once external effects during a crash. Do not implement adapters with independent executors or irreversible external actions.

## Scheduling and recovery

The user replaced the original fortnightly cadence with daily runs on 2026-09-16. The first run is **17 September 2026 at 13:00 SGT (05:00 UTC)**, then every day at that time. Flyway V3 updates existing coordinator dates without deleting execution history.

For the local scheduled run, keep the backend, Docker and computer running and awake. Closing the browser is fine. If the backend is offline at the due time, the catch-up runs after recovery. Scheduled results are available at `GET /api/admin/ingestion/runs?trigger=SCHEDULED`. The enabled simulated failure fixture intentionally makes the combined demo run report `PARTIAL_FAILURE` with 3 accepted payloads and 1 exception stack.

Set `INGESTION_SCHEDULING_ENABLED=true`, `INGESTION_ANCHOR` to the first due UTC instant (for example `2026-09-17T05:00:00Z`), and an enabled-source list. Scheduling is disabled in the default production profile and enabled in `ingestion-demo`, unless explicitly overridden by `INGESTION_SCHEDULING_ENABLED=false`. The persisted anchor is authoritative after initialization; changing the environment does not silently reset an existing schedule. A deliberate schedule change needs a reviewed migration of coordinator state.

The next slot is always previous scheduled slot +24 hours, independent of duration or manual runs. Spring TaskScheduler arms that instant. A ten-second reconciliation sweep repairs dispatch after restarts; it does not scrape every ten seconds. Concurrent replicas serialize admission through a short PostgreSQL row lock.

If the backend was offline, one run represents the most recent overdue slot; `missedSlots` records older coalesced slots. A due slot waits while another run is active. Run metadata captures actual start and lateness. One durable admission per due slot is guaranteed; exact execution at that instant requires a healthy running process and database.

Accepted work is durable before dispatch. If dispatch never occurs, the next sweep can claim it. Running work has a unique owner, ten-second heartbeat and 90-second lease. Expired running work becomes `INTERRUPTED` and is not blindly replayed. All progress/finalization writes check owner and lease. The schedule retains its cadence after interruption; an admin may initiate a new run when appropriate.

`backend/fly.toml` disables automatic stopping and starting so deployed Machines keep running without HTTP traffic. This incurs ongoing runtime cost once deployed. No deployment was performed by this change. Keep machine clocks synchronized; due times/leases use UTC application clocks. Retain coordinator and active-run rows if adding log retention later.

## Database and metadata

Flyway V2 creates `system_log`. The reserved `INGESTION_COORDINATOR` row stores scheduling, active ownership and source cooldowns. `INGESTION_RUN` rows store execution history. `INGESTION_DEMO_PAYLOAD` rows are simulation receipts only. No extra domain tables or product schema are created.

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

Open `http://localhost:5173/admin/ingestion`, enter the configured password, select sources and click Run now. The default source fixtures produce three accepted payloads; selecting the failure fixture as well gives one captured exception. Production scheduling stays disabled in this manual demo. Fake-clock PostgreSQL tests exercise the scheduling admission path without shortening the real 24-hour interval.

## References

- [Spring task execution and scheduling](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)
- [Spring Security filter chain configuration](https://docs.spring.io/spring-security/reference/servlet/configuration/java.html)
- [Spring Security CSRF protection](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
- [Fly.io autostop/autostart](https://fly.io/docs/launch/autostop-autostart/)
