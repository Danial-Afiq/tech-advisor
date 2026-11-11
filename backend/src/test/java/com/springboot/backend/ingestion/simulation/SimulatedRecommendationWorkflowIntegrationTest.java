package com.springboot.backend.ingestion.simulation;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.springboot.backend.email.RecommendationEmailSender;
import com.springboot.backend.model.User;
import com.springboot.backend.recommendation.AiAssessmentClient;
import com.springboot.backend.recommendation.AssessRequest;
import com.springboot.backend.recommendation.AssessResponse;
import com.springboot.backend.recommendation.CandidatePruningService;
import com.springboot.backend.recommendation.trigger.RecommendationTriggerConfiguration;
import com.springboot.backend.repository.UserRepository;
import com.springboot.backend.service.JwtService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Complete SCRUM-7 price-drop path with real persistence, triggering and dashboard reads. */
@SpringBootTest(properties = {
        "ingestion.reconciliation-enabled=false",
        "ingestion.scheduling-enabled=false",
        "logging.level.root=WARN",
        "recommendation.triggers.ai-assessment-enabled=true"
})
@ActiveProfiles("ingestion-demo")
@DirtiesContext
class SimulatedRecommendationWorkflowIntegrationTest {

    private static final String TEST_BRAND = "SCRUM7-E2E";
    private static final String EMAIL_PREFIX = "scrum7-e2e-";
    private static final OffsetDateTime BASELINE_AT =
            OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    @Autowired private SimulatedMarketChangeService changes;
    @Autowired private CandidatePruningService candidates;
    @Autowired private UserRepository users;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate db;
    @Autowired private WebApplicationContext web;
    @Autowired @Qualifier(RecommendationTriggerConfiguration.EXECUTOR)
    private ThreadPoolTaskExecutor executor;

    @MockitoBean private AiAssessmentClient aiClient;
    @MockitoBean private RecommendationEmailSender emailSender;

    private MockMvc mvc;
    private User user;
    private Long ownedProductId;
    private Long candidateProductId;
    private Long deviceId;
    private Long eventId;

    @BeforeEach
    void setUp() throws InterruptedException {
        assertTrue(
                db.queryForObject("SELECT current_database()", String.class).endsWith("_test"),
                "The end-to-end demo test requires a dedicated _test database");
        removeAbandonedFixture();
        reset(aiClient, emailSender);
        when(aiClient.assess(any())).thenAnswer(invocation -> aiSuccess(invocation.getArgument(0)));
        mvc = MockMvcBuilders.webAppContextSetup(web)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        awaitIdle();
        removeAbandonedFixture();
    }

    @Test
    void simulatedPriceDropChangesTheRealRecommendationAndDashboard() throws Exception {
        String unique = Long.toUnsignedString(System.nanoTime());
        user = users.save(new User(EMAIL_PREFIX + unique + "@example.test", "{noop}unused", "USER"));
        ownedProductId = phone(
                "Older Phone " + unique, LocalDate.of(2021, 1, 15),
                3700, 8, 60, 220, 128, "3200", "500.00");
        candidateProductId = phone(
                "Better Phone " + unique, LocalDate.of(2025, 2, 7),
                5500, 16, 144, 180, 512, "9200", "1300.00");
        deviceId = ownedDevice(user.getId(), ownedProductId);

        assertFalse(candidates.getViableCandidates(deviceId).stream()
                .anyMatch(candidate -> candidateProductId.equals(candidate.getProductId())),
                "S$1,300 candidate must be outside the S$1,200 budget");
        assertEquals(0, activeRecommendations());

        var result = changes.injectPrice(new SimulatedMarketChangeService.PriceCommand(
                candidateProductId, new BigDecimal("1100.00"), "scrum7-batch3-" + unique));
        eventId = result.marketEventId();

        assertFalse(result.alreadyApplied());
        assertEquals(new BigDecimal("1100.00"), db.queryForObject(
                """
                SELECT price FROM price_history WHERE product_id = ?
                ORDER BY observed_at DESC, id DESC LIMIT 1
                """,
                BigDecimal.class, candidateProductId));

        Map<String, Object> event = db.queryForMap(
                """
                SELECT event_type, source, old_value ->> 'price' AS old_price,
                       new_value ->> 'price' AS new_price
                FROM market_events WHERE id = ?
                """,
                eventId);
        assertEquals("PRICE_CHANGE", event.get("event_type"));
        assertEquals(result.source(), event.get("source"));
        assertEquals("1300.00", event.get("old_price"));
        assertEquals("1100.00", event.get("new_price"));

        await(() -> activeRecommendations() == 1, "active recommendation after price drop");
        await(() -> triggerLogCount() == 1, "one completed market-event trigger");

        assertTrue(candidates.getViableCandidates(deviceId).stream()
                .anyMatch(candidate -> candidateProductId.equals(candidate.getProductId())
                        && candidate.getLatestPrice().compareTo(new BigDecimal("1100.00")) == 0));
        assertEquals(eventId, db.queryForObject(
                """
                SELECT trigger_event_id FROM recommendations
                WHERE current_device_id = ? AND candidate_product_id = ? AND status = 'ACTIVE'
                """,
                Long.class, deviceId, candidateProductId));

        String token = jwtService.generateToken(user);
        mvc.perform(get("/api/dashboard/recommendations")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].currentDeviceId").value(deviceId))
                .andExpect(jsonPath("$[0].candidateProductId").value(candidateProductId))
                .andExpect(jsonPath("$[0].candidateModelName").value("Better Phone " + unique))
                .andExpect(jsonPath("$[0].latestPrice").value(1100.00))
                .andExpect(jsonPath("$[0].currency").value("SGD"));

        verify(aiClient, times(1)).assess(any());
    }

    private Long phone(
            String modelName,
            LocalDate releaseDate,
            int batteryMah,
            int ramGb,
            int refreshRateHz,
            int weightG,
            int storageGb,
            String benchmark,
            String price) {

        Long productId = db.queryForObject(
                """
                INSERT INTO products (brand, model_name, category, status, release_date)
                VALUES (?, ?, 'SMARTPHONE', 'VERIFIED', ?) RETURNING id
                """,
                Long.class, TEST_BRAND, modelName, releaseDate);
        db.update(
                """
                INSERT INTO phone (product_id, battery_mah, ram_gb, refresh_rate_hz, weight_g, storage_gb)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                productId, batteryMah, ramGb, refreshRateHz, weightG, storageGb);
        db.update(
                """
                INSERT INTO benchmark_results
                    (product_id, benchmark_name, score, unit, higher_is_better, source, observed_at)
                VALUES (?, 'geekbench_multi', CAST(? AS NUMERIC), 'points', true, 'batch3-test', ?)
                """,
                productId, benchmark, BASELINE_AT);
        db.update(
                """
                INSERT INTO price_history (product_id, price, currency, source, observed_at)
                VALUES (?, CAST(? AS NUMERIC), 'SGD', 'batch3-test', ?)
                """,
                productId, price, BASELINE_AT);
        return productId;
    }

    private Long ownedDevice(long userId, long productId) {
        Long id = db.queryForObject(
                """
                INSERT INTO user_devices
                    (user_id, product_id, custom_name, purchase_date, condition,
                     satisfaction_score, use_cases, is_current)
                VALUES (?, ?, 'My older phone', DATE '2022-01-15', 'FAIR', 45, '["gaming"]', true)
                RETURNING id
                """,
                Long.class, userId, productId);
        db.update(
                """
                INSERT INTO device_preferences
                    (user_device_id, budget, currency, upgrade_urgency, brand_flexibility, priorities)
                VALUES (?, 1200.00, 'SGD', 'SOMEWHAT_URGENT', 'FLEXIBLE', '{"battery":5,"performance":4}')
                """,
                id);
        return id;
    }

    private int activeRecommendations() {
        if (deviceId == null || candidateProductId == null) {
            return 0;
        }
        return db.queryForObject(
                """
                SELECT count(*) FROM recommendations
                WHERE current_device_id = ? AND candidate_product_id = ? AND status = 'ACTIVE'
                """,
                Integer.class, deviceId, candidateProductId);
    }

    private int triggerLogCount() {
        if (eventId == null) {
            return 0;
        }
        return db.queryForObject(
                """
                SELECT count(*) FROM system_log
                WHERE component = 'TRIGGER_MARKET_EVENT'
                  AND (metadata ->> 'market_event_id')::bigint = ?
                  AND status = 'SUCCESS'
                """,
                Integer.class, eventId);
    }

    private void removeAbandonedFixture() throws InterruptedException {
        awaitIdle();
        db.update("DELETE FROM system_log WHERE component = 'RECOMMENDATION_EMAIL' "
                + "AND (metadata ->> 'userId')::bigint IN "
                + "(SELECT id FROM users WHERE email LIKE ?)", EMAIL_PREFIX + "%");
        db.update("DELETE FROM system_log WHERE component = 'NOTIFICATIONS' "
                + "AND (metadata ->> 'user_id')::bigint IN "
                + "(SELECT id FROM users WHERE email LIKE ?)", EMAIL_PREFIX + "%");
        db.update("DELETE FROM system_log WHERE component = 'TRIGGER_MARKET_EVENT' "
                + "AND (metadata ->> 'product_id')::bigint IN "
                + "(SELECT id FROM products WHERE brand = ?)", TEST_BRAND);
        db.update("DELETE FROM market_events WHERE product_id IN "
                + "(SELECT id FROM products WHERE brand = ?)", TEST_BRAND);
        db.update("DELETE FROM users WHERE email LIKE ?", EMAIL_PREFIX + "%");
        db.update("DELETE FROM products WHERE brand = ?", TEST_BRAND);
        user = null;
        ownedProductId = null;
        candidateProductId = null;
        deviceId = null;
        eventId = null;
    }

    private void awaitIdle() throws InterruptedException {
        await(() -> executor.getActiveCount() == 0
                && executor.getThreadPoolExecutor().getQueue().isEmpty(), "trigger executor to drain");
    }

    private static void await(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Timed out waiting for " + description);
    }

    private static AssessResponse aiSuccess(AssessRequest request) {
        return new AssessResponse(
                request.requestId(), "B",
                List.of(new AssessResponse.EvidenceFinding(
                        "battery", "POSITIVE", List.of("P1"), List.of(4412L),
                        "Owners report good endurance.")),
                List.of(), List.of(), "Owners are broadly positive.",
                new AssessResponse.ResponseMeta(
                        "batch3-test-model", "v1", List.of(4412L), 0,
                        Map.of("k", 12, "chunk_char_cap", 800, "vector_store", "local", "embedding_dim", 512),
                        false, null),
                null);
    }
}
