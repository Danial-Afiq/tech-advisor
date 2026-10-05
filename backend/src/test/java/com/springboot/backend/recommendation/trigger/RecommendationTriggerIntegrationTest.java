package com.springboot.backend.recommendation.trigger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import com.springboot.backend.dto.DeviceRequest;
import com.springboot.backend.dto.DeviceResponse;
import com.springboot.backend.marketevent.MarketEvent;
import com.springboot.backend.marketevent.MarketEventService;
import com.springboot.backend.marketevent.MarketEventType;
import com.springboot.backend.recommendation.AiAssessmentClient;
import com.springboot.backend.recommendation.AiServiceException;
import com.springboot.backend.recommendation.AssessRequest;
import com.springboot.backend.recommendation.AssessResponse;
import com.springboot.backend.recommendation.classification.TierMapper;
import com.springboot.backend.service.DeviceService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Both triggers end to end: real save paths, real after-commit listeners, the
 * real single-thread executor and real PostgreSQL. Only the AI service client
 * is mocked - no live model call, no API cost.
 *
 * <p>Deliberately <b>not</b> {@code @Transactional}: the triggers fire only after
 * a real commit. Each test waits for the executor to drain and removes what it
 * committed.
 */
@SpringBootTest(properties = {
    "ingestion.reconciliation-enabled=false",
    "ingestion.scheduling-enabled=false",
    "logging.level.root=WARN",
    // Off for the test phase by default (pom.xml); safe here because the client is mocked.
    "recommendation.triggers.ai-assessment-enabled=true"
})
class RecommendationTriggerIntegrationTest {

    private static final OffsetDateTime OBSERVED = OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final String EMAIL_PATTERN = "rtrig-%@example.test";

    @Autowired private MarketEventService marketEvents;
    @Autowired private DeviceService deviceService;
    @Autowired private RecommendationTriggerService triggers;
    @Autowired private JdbcTemplate db;
    @Autowired @Qualifier(RecommendationTriggerConfiguration.EXECUTOR) private ThreadPoolTaskExecutor executor;

    @MockitoBean private AiAssessmentClient aiClient;

    private Long olderPhone;
    private Long flagship;
    private Long newerFlagship;

    private Long upgrader1;
    private Long upgrader2;
    private Long alreadyBetter;
    private Long noSmartphone;
    private Long retired;
    private Long noBudget;

    @BeforeEach
    void seed() throws InterruptedException {
        assertTrue(
                db.queryForObject("SELECT current_database()", String.class).endsWith("_test"),
                "Integration tests require a dedicated database whose name ends in _test");
        cleanUp();

        when(aiClient.assess(any())).thenAnswer(call -> success(call.getArgument(0)));

        olderPhone = phone("Galaxy S22", "700.00", 3700, 8, 60, 220, 128, "3200");
        flagship = phone("Galaxy S25 Ultra", "1099.00", 5500, 16, 144, 180, 512, "9200");
        newerFlagship = phone("Galaxy S26 Ultra", "1399.00", 6000, 16, 144, 170, 1024, "9800");

        upgrader1 = device(user(), olderPhone, "upgrader-1", true, true);
        upgrader2 = device(user(), olderPhone, "upgrader-2", true, true);
        alreadyBetter = device(user(), newerFlagship, "already-better", true, true);
        noSmartphone = device(user(), null, "a laptop we do not stock", true, true);
        retired = device(user(), olderPhone, "retired", false, true);
        noBudget = device(user(), olderPhone, "no-budget", true, false);
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        awaitIdle();
        db.update("DELETE FROM recommendations");
        db.update("DELETE FROM system_log WHERE component LIKE 'TRIGGER_%'");
        db.update("DELETE FROM market_events");
        db.update("DELETE FROM user_devices");
        db.update("DELETE FROM products");
        db.update("DELETE FROM users WHERE email LIKE ?", EMAIL_PATTERN);
    }

    // AC1 + AC2 + AC5
    @Test
    void aMarketEventReachesEveryCurrentSmartphoneLinkedToTheEventAndNobodyElse() throws InterruptedException {
        MarketEvent event = priceDrop(flagship);
        Map<String, Object> log = awaitMarketEventLog(event.id());

        // AC1: one ACTIVE recommendation per affected device, each linked to the event.
        for (Long device : List.of(upgrader1, upgrader2, alreadyBetter)) {
            Map<String, Object> row = activeRow(device, flagship);
            assertEquals(event.id(), ((Number) row.get("trigger_event_id")).longValue());
        }
        // Every history row the run wrote carries the event link, deterministic and AI alike.
        assertEquals(0, db.queryForObject(
                "SELECT count(*) FROM recommendations WHERE trigger_event_id IS DISTINCT FROM ?",
                Integer.class, event.id()));

        // AC2: no relevant current device, no recommendation.
        for (Long device : List.of(noSmartphone, retired, noBudget)) {
            assertEquals(0, rowCount(device, null), "device " + device + " should not be affected");
        }

        // AC5: the early-rejected pair is NO_MEANINGFUL_CHANGE and never reached the model.
        Map<String, Object> rejected = activeRow(alreadyBetter, flagship);
        assertEquals(TierMapper.NO_MEANINGFUL_CHANGE, rejected.get("verdict"));
        assertNull(rejected.get("confidence"));
        verify(aiClient, never()).assess(argThat(r -> "already-better".equals(r.userContext().ownedDevice().name())));
        verify(aiClient, times(2)).assess(any());

        // The pairs past the gate were assessed and carry the event to the model.
        assertNotNull(activeRow(upgrader1, flagship).get("ai_model"));
        verify(aiClient, times(2)).assess(argThat(r ->
                r.computed().triggerEvent() != null && "PRICE_CHANGE".equals(r.computed().triggerEvent().eventType())));

        assertEquals("SUCCESS", log.get("status"));
        assertEquals("3", log.get("pairs_selected"));
        assertEquals("2", log.get("pairs_assessed"));
        assertEquals("1", log.get("pairs_rejected_early"));
        assertEquals("1", log.get("devices_without_preferences"));
    }

    // AC6
    @Test
    void reFiringTheSameEventLeavesExactlyOneActiveRowPerPair() throws InterruptedException {
        MarketEvent event = priceDrop(flagship);
        awaitMarketEventLog(event.id());

        triggers.runForMarketEvent(event.id(), RecommendationTriggerService.CAUSE_ADMIN);

        for (Long device : List.of(upgrader1, upgrader2, alreadyBetter)) {
            assertEquals(1, rowCount(device, "ACTIVE"), "device " + device);
            assertTrue(rowCount(device, "SUPERSEDED") >= 1, "the earlier answer is kept as history");
        }
        assertEquals(0, db.queryForObject(
                """
                SELECT count(*) FROM (
                    SELECT user_id, candidate_product_id FROM recommendations
                    WHERE status = 'ACTIVE' GROUP BY 1, 2 HAVING count(*) > 1) duplicates
                """,
                Integer.class));
    }

    // AC7
    @Test
    void oneAiFailureDoesNotStopTheBatchAndIsLogged() throws InterruptedException {
        doThrow(new AiServiceException("AI service call failed: timeout", null))
                .when(aiClient).assess(argThat(r -> r != null && "upgrader-1".equals(r.userContext().ownedDevice().name())));

        MarketEvent event = priceDrop(flagship);
        Map<String, Object> log = awaitMarketEventLog(event.id());

        assertEquals("PARTIAL_SUCCESS", log.get("status"));
        assertEquals("1", log.get("pairs_failed"));
        Map<String, Object> failure = db.queryForMap(
                "SELECT metadata -> 'failures' -> 0 ->> 'user_device_id' AS device, "
                        + "metadata -> 'failures' -> 0 ->> 'stage' AS stage "
                        + "FROM system_log WHERE component = 'TRIGGER_MARKET_EVENT' "
                        + "AND (metadata ->> 'market_event_id')::bigint = ?",
                event.id());
        assertEquals(upgrader1.toString(), failure.get("device"));
        assertEquals("AI", failure.get("stage"));

        // The failed pair keeps its deterministic answer; the other pair was assessed.
        assertNull(activeRow(upgrader1, flagship).get("ai_model"));
        assertNotNull(activeRow(upgrader2, flagship).get("ai_model"));
    }

    // AC3 + AC4
    @Test
    void addingADeviceEvaluatesTheCatalogueForThatUserOnly() throws InterruptedException {
        MarketEvent event = priceDrop(flagship);
        awaitMarketEventLog(event.id());
        List<Map<String, Object>> othersBefore = otherUsersRows();

        String email = "rtrig-adder-" + System.nanoTime() + "@example.test";
        db.update("INSERT INTO users (email, role, password_hash) VALUES (?, 'USER', '{noop}unused')", email);
        DeviceRequest request = new DeviceRequest();
        request.setProductId(olderPhone);
        request.setBudget(new BigDecimal("1500.00"));
        DeviceResponse added = deviceService.createDevice(email, request);

        Map<String, Object> log = awaitDeviceLog(added.getId());

        // AC3: recommendations against the catalogue, with no event link.
        assertTrue(rowCount(added.getId(), "ACTIVE") >= 2, "both affordable smartphones are evaluated");
        assertEquals(0, db.queryForObject(
                "SELECT count(*) FROM recommendations WHERE current_device_id = ? AND trigger_event_id IS NOT NULL",
                Integer.class, added.getId()));
        assertEquals("DEVICE_ADDED", log.get("cause"));
        // The API cannot record urgency or brand flexibility yet, so the AI call is skipped, not invented.
        assertEquals("SUCCESS", log.get("status"));
        assertTrue(Integer.parseInt((String) log.get("pairs_ai_skipped")) >= 1);

        // AC4: nobody else's recommendations changed.
        assertEquals(othersBefore, otherUsersRows());
    }

    // AC8
    @Test
    void savesReturnWithoutWaitingForTheBatch() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            release.await(30, TimeUnit.SECONDS);
            return success(call.getArgument(0));
        }).when(aiClient).assess(any());

        try {
            MarketEvent event = priceDrop(flagship);
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the trigger never started");
            assertEquals(1, release.getCount(), "record() returned while the AI call was still blocked");

            // The executor is now busy; adding a device must still return immediately.
            String email = "rtrig-async-" + System.nanoTime() + "@example.test";
            db.update("INSERT INTO users (email, role, password_hash) VALUES (?, 'USER', '{noop}unused')", email);
            DeviceRequest request = new DeviceRequest();
            request.setProductId(olderPhone);
            request.setBudget(new BigDecimal("1500.00"));
            DeviceResponse added = deviceService.createDevice(email, request);
            assertEquals(0, rowCount(added.getId(), null), "the device run waits behind the blocked one");

            release.countDown();
            awaitMarketEventLog(event.id());
            awaitDeviceLog(added.getId());
            assertTrue(rowCount(added.getId(), null) > 0);
        } finally {
            release.countDown();
        }
    }

    // --- helpers ------------------------------------------------------------

    private MarketEvent priceDrop(Long productId) {
        return marketEvents.record(new MarketEventService.NewMarketEvent(
                productId, MarketEventType.PRICE_CHANGE, "Galaxy S25 Ultra drops to S$1099", null,
                Map.of("price", 1199), Map.of("price", 1099), "test"));
    }

    private Map<String, Object> awaitMarketEventLog(long eventId) throws InterruptedException {
        return awaitLog("TRIGGER_MARKET_EVENT", "market_event_id", eventId);
    }

    private Map<String, Object> awaitDeviceLog(long deviceId) throws InterruptedException {
        return awaitLog("TRIGGER_DEVICE_INVENTORY", "user_device_id", deviceId);
    }

    /** The run's one system_log row, flattened to text values, once it exists. */
    private Map<String, Object> awaitLog(String component, String key, long id) throws InterruptedException {
        String sql = """
                SELECT status, metadata ->> 'cause' AS cause,
                       metadata ->> 'pairs_selected' AS pairs_selected,
                       metadata ->> 'pairs_assessed' AS pairs_assessed,
                       metadata ->> 'pairs_rejected_early' AS pairs_rejected_early,
                       metadata ->> 'pairs_ai_skipped' AS pairs_ai_skipped,
                       metadata ->> 'pairs_failed' AS pairs_failed,
                       metadata ->> 'devices_without_preferences' AS devices_without_preferences
                FROM system_log WHERE component = ? AND (metadata ->> '%s')::bigint = ?
                """.formatted(key);
        await(() -> !db.queryForList(sql, component, id).isEmpty(), component + " row for " + id);
        List<Map<String, Object>> rows = db.queryForList(sql, component, id);
        assertEquals(1, rows.size(), "exactly one system_log row per run");
        return rows.getFirst();
    }

    private void awaitIdle() throws InterruptedException {
        await(() -> executor.getActiveCount() == 0 && executor.getThreadPoolExecutor().getQueue().isEmpty(),
                "trigger executor to drain");
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Timed out waiting for " + what);
    }

    private Map<String, Object> activeRow(Long deviceId, Long productId) {
        return db.queryForMap(
                "SELECT * FROM recommendations WHERE current_device_id = ? AND candidate_product_id = ? AND status = 'ACTIVE'",
                deviceId, productId);
    }

    private int rowCount(Long deviceId, String status) {
        return status == null
                ? db.queryForObject("SELECT count(*) FROM recommendations WHERE current_device_id = ?", Integer.class, deviceId)
                : db.queryForObject("SELECT count(*) FROM recommendations WHERE current_device_id = ? AND status = ?",
                        Integer.class, deviceId, status);
    }

    private List<Map<String, Object>> otherUsersRows() {
        return db.queryForList(
                "SELECT id, status, verdict, confidence FROM recommendations WHERE current_device_id IN (?, ?, ?) ORDER BY id",
                upgrader1, upgrader2, alreadyBetter);
    }

    private static AssessResponse success(AssessRequest request) {
        return new AssessResponse(
                request.requestId(), "B", List.of(), List.of(), List.of(), "Owners are broadly positive.",
                new AssessResponse.ResponseMeta("test-model", "v1", List.of(), 0, Map.of(), false, null),
                null);
    }

    private Long user() {
        return db.queryForObject(
                "INSERT INTO users (email, role, password_hash) VALUES (?, 'USER', '{noop}unused') RETURNING id",
                Long.class, "rtrig-" + System.nanoTime() + "@example.test");
    }

    /** An owned device with the full AI context, so a pair past the gate really reaches the client. */
    private Long device(Long userId, Long productId, String name, boolean current, boolean withPreferences) {
        Long id = db.queryForObject(
                """
                INSERT INTO user_devices (user_id, product_id, custom_name, purchase_date, condition,
                                          satisfaction_score, use_cases, is_current)
                VALUES (?, ?, ?, ?, 'FAIR', 45, '["gaming"]', ?) RETURNING id
                """,
                Long.class, userId, productId, name, LocalDate.of(2024, 1, 15), current);
        if (withPreferences) {
            db.update(
                    """
                    INSERT INTO device_preferences (user_device_id, budget, currency, upgrade_urgency,
                                                    brand_flexibility, priorities)
                    VALUES (?, 1500.00, 'SGD', 'SOMEWHAT_URGENT', 'FLEXIBLE', '{"battery": 5}')
                    """,
                    id);
        }
        return id;
    }

    /** A priced, spec'd, benchmarked smartphone. */
    private Long phone(
            String modelName, String price,
            int batteryMah, int ramGb, int refreshRateHz, int weightG, int storageGb, String geekbench) {

        Long id = db.queryForObject(
                """
                INSERT INTO products (brand, model_name, category, status, release_date)
                VALUES ('Samsung', ?, 'SMARTPHONE', 'VERIFIED', DATE '2025-02-07') RETURNING id
                """,
                Long.class, modelName);
        db.update(
                """
                INSERT INTO phone (product_id, battery_mah, ram_gb, refresh_rate_hz, weight_g, storage_gb)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                id, batteryMah, ramGb, refreshRateHz, weightG, storageGb);
        db.update(
                """
                INSERT INTO benchmark_results
                    (product_id, benchmark_name, score, unit, higher_is_better, source, observed_at)
                VALUES (?, 'geekbench_multi', CAST(? AS NUMERIC), 'points', true, 'test', ?)
                """,
                id, geekbench, OBSERVED);
        db.update(
                """
                INSERT INTO price_history (product_id, price, currency, source, observed_at)
                VALUES (?, CAST(? AS NUMERIC), 'SGD', 'test', ?)
                """,
                id, price, OBSERVED);
        return id;
    }
}
