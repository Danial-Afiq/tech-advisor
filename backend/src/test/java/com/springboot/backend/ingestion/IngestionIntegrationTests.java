package com.springboot.backend.ingestion;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"ingestion.demo-password=test-password-only", "ingestion.reconciliation-enabled=false",
        "ingestion.anchor=2026-09-17T05:00:00Z", "logging.level.root=WARN", "debug=false",
        "ingestion.enabled-sources=simulated-release,simulated-failure,test-quality"})
@ActiveProfiles("ingestion-demo")
@Import(IngestionIntegrationTests.TimeConfig.class)
class IngestionIntegrationTests {
    static final Instant ANCHOR = Instant.parse("2026-09-17T05:00:00Z");
    @TestConfiguration static class TimeConfig {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
        @Bean IngestionSource qualitySource() {
            return new IngestionSource() {
                public String sourceId() { return "test-quality"; }
                public boolean simulation() { return true; }
                public void ingest(SourceContext context, java.util.function.Consumer<Payload> output) {
                    var valid = new Payload(sourceId(), "same-item", context.now(),
                            new Payload.Price("phone", java.math.BigDecimal.ONE, "SGD"));
                    output.accept(valid); output.accept(valid);
                    output.accept(new Payload("wrong-source", "bad", context.now(), valid.body()));
                    throw new IllegalStateException("Do not expose this source message or secret");
                }
            };
        }
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(ANCHOR);
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    }
    @Autowired RunStore store;
    @Autowired IngestionOrchestrator runner;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate db;
    @Autowired WebApplicationContext web;
    MockMvc mvc;

    @BeforeEach void reset() throws Exception {
        assertTrue(db.queryForObject("SELECT current_database()", String.class).endsWith("_test"),
                "Integration tests require a dedicated database whose name ends in _test");
        // All asynchronous runs in this class are awaited before the next test.
        db.update("DELETE FROM system_log WHERE component LIKE 'INGESTION_%'");
        clock.now.set(ANCHOR);
        mvc = MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
    }
    @Test void anchoredScheduleAndManualRunDoNotDrift() {
        clock.now.set(ANCHOR.minusSeconds(1));
        assertNull(store.admit(List.of("simulated-release"), "scheduler", null, null, true, true));
        clock.now.set(ANCHOR);
        var first = store.admit(List.of("simulated-release"), "scheduler", null, null, true, true);
        assertEquals(ANCHOR, first.scheduledFor);
        var claimed = store.claim("owner");
        assertNull(store.admit(List.of("simulated-release"), "scheduler", null, null, true, true));
        claimed.status = "SUCCESS"; store.finish(claimed, "owner");
        clock.now.set(ANCHOR.plus(Duration.ofHours(3)));
        var manual = store.admit(List.of("simulated-release"), "admin", "manual-key", "Release", false, true);
        assertEquals(manual.runId, store.admit(List.of("simulated-release"), "admin", "manual-key", "Release", false, true).runId);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> store.admit(List.of("simulated-release"), "admin", "manual-key", "Changed", false, true));
        assertEquals(ANCHOR.plus(RunStore.INTERVAL), store.state().nextDue);
        assertEquals(Duration.ofDays(14), RunStore.INTERVAL);
    }
    @Test void downtimeCoalescesAndExpiredOwnerCannotWrite() {
        // 3 missed intervals plus a bit, expressed relative to INTERVAL so this test's intent
        // (three coalesced slots) survives any future cadence change without hand-recomputed dates.
        clock.now.set(ANCHOR.plus(RunStore.INTERVAL.multipliedBy(3)).plusSeconds(3600));
        var run = store.admit(List.of("simulated-release"), "scheduler", null, null, true, true);
        assertEquals(3, run.missedSlots);
        assertEquals(ANCHOR.plus(RunStore.INTERVAL.multipliedBy(3)), run.scheduledFor);
        assertEquals(ANCHOR.plus(RunStore.INTERVAL.multipliedBy(4)), store.state().nextDue);
        var stale = store.claim("old-owner");
        clock.now.set(clock.instant().plusSeconds(91));
        assertNull(store.claim("new-owner"));
        assertEquals("INTERRUPTED", store.get(stale.runId).status);
        assertThrows(IllegalStateException.class, () -> store.progress(stale, "old-owner"));
        assertThrows(IllegalStateException.class, () -> store.heartbeat(stale.runId, "old-owner"));
    }
    @Test void concurrentAdmissionsHaveOneWinnerAndAcceptedWorkSurvivesDispatcherLoss() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<RunLog> submit = () -> { gate.await(); return store.admit(List.of("simulated-release"), "scheduler", null, null, true, true); };
            var a = pool.submit(submit); var b = pool.submit(submit); gate.countDown();
            assertEquals(1, Arrays.asList(a.get(), b.get()).stream().filter(Objects::nonNull).count());
        }
        clock.now.set(clock.instant().plusSeconds(100));
        var claimed = store.claim("recovered-dispatcher");
        assertNotNull(claimed);
        assertEquals("RUNNING", claimed.status);
        assertNull(store.claim("other-dispatcher"));
    }
    @Test void sourceCooldownIsSharedAcrossRuns() {
        var adapter = new IngestionSource() {
            public String sourceId() { return "test-source"; }
            public void ingest(SourceContext context, java.util.function.Consumer<Payload> output) {}
        };
        store.admit(List.of("test-source"), "admin", "cooldown-key", null, false, true);
        var first = store.claim("one");
        assertTrue(store.startSource(first.runId, "one", adapter));
        first.status = "SUCCESS"; store.finish(first, "one");
        store.admit(List.of("test-source"), "admin", "cooldown-key-2", null, false, true);
        var second = store.claim("two");
        assertFalse(store.startSource(second.runId, "two", adapter));
        clock.now.set(clock.instant().plusSeconds(30));
        assertFalse(store.startSource(second.runId, "two", adapter));
    }
    @Test void manualRunPersistsMixedTypedPayloadsAndFailureMetadata() throws Exception {
        var admitted = runner.manual(List.of("simulated-release", "simulated-failure"), "admin", "sample-run-key", "Mid-cycle demo");
        var run = await(admitted.runId);
        assertEquals("PARTIAL_FAILURE", run.status);
        assertEquals(3, run.processedPayloadCount); assertEquals(1, run.errorStackCount);
        assertEquals(1, run.errorCount); assertNotNull(run.startedAt); assertNotNull(run.finishedAt);
        assertEquals(2, run.sources.size());
        assertEquals(3, db.queryForObject("SELECT count(*) FROM system_log WHERE component='INGESTION_DEMO_PAYLOAD'", Integer.class));
        assertEquals(3, db.queryForObject("SELECT count(DISTINCT metadata->>'payloadType') FROM system_log WHERE component='INGESTION_DEMO_PAYLOAD'", Integer.class));
        assertNull(store.state().activeRunId);
        assertFalse(run.sources.getFirst().errors.toString().contains("Deliberate fixture failure"));
    }
    @Test void duplicateRejectedAndExceptionCountersAreDistinct() throws Exception {
        var run = await(runner.manual(List.of("test-quality"), "admin", "quality-key", null).runId);
        assertEquals(1, run.processedPayloadCount); assertEquals(1, run.duplicatePayloadCount);
        assertEquals(1, run.rejectedPayloadCount); assertEquals(2, run.errorCount); assertEquals(1, run.errorStackCount);
        assertEquals("PARTIAL_FAILURE", run.status);
        assertFalse(run.sources.getFirst().errors.toString().contains("secret"));
    }
    @Test void dueScheduleWaitsForManualAndLongRetryAfterPersists() {
        var run = store.admit(List.of("simulated-release"), "admin", "overlap-key", null, false, true);
        var active = store.claim("manual-owner");
        assertNull(store.admit(List.of("simulated-release"), "scheduler", null, null, true, true));
        assertEquals(ANCHOR, store.state().nextDue);
        store.deferSource(run.runId, "manual-owner", "simulated-release", ANCHOR.plus(Duration.ofHours(2)));
        assertEquals(ANCHOR.plus(Duration.ofHours(2)), store.state().nextAllowed.get("simulated-release"));
        active.status = "SUCCESS"; store.finish(active, "manual-owner");
        assertNotNull(store.admit(List.of("simulated-release"), "scheduler", null, null, true, true));
    }
    @Test void adminAuthorizationCsrfValidationAndAsyncResponse() throws Exception {
        mvc.perform(get("/api/admin/ingestion/session").with(httpBasic("demo-admin", "test-password-only")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("demo-admin"))
                .andExpect(jsonPath("$.csrfToken").isNotEmpty());
        mvc.perform(get("/api/admin/ingestion/session").with(httpBasic("demo-admin", "wrong-password")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/ingestion/runs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/ingestion/runs").with(user("ordinary").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/ingestion/runs").with(user("admin").roles("ADMIN"))
                .header("Idempotency-Key", "test-api-key").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/ingestion/runs").with(user("admin").roles("ADMIN")).with(csrf())
                .header("Idempotency-Key", "test-api-key").contentType("application/json").content("{\"sources\":[\"unknown\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/ingestion/runs").with(user("admin").roles("ADMIN")).with(csrf())
                .header("Idempotency-Key", "test-api-key").contentType("application/json").content("{\"sources\":[\"simulated-release\"]}"))
                .andExpect(status().isAccepted()).andExpect(header().exists("Location"));
        var run = await(store.history(0, 1, "", "").getFirst().runId);
        assertEquals("admin", run.requestedBy); assertEquals("SUCCESS", run.status);
        mvc.perform(get("/api/admin/ingestion/runs?size=101").with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
    }
    private RunLog await(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            var run = store.get(id);
            if (run.finishedAt != null) { Thread.sleep(50); return run; }
            Thread.sleep(50);
        }
        fail("Run did not finish"); return null;
    }
}
