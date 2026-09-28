package com.springboot.backend.recommendation;

import static org.junit.jupiter.api.Assertions.*;

import com.springboot.backend.exception.ResourceNotFoundException;
import com.springboot.backend.recommendation.classification.TierMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shortlisting and classification wired together against real PostgreSQL:
 * what reaches the classifier, what it does with a bad candidate, and the
 * order the result comes back in.
 *
 * <p>Runs inside a transaction that rolls back, and refuses to run outside a
 * dedicated {@code *_test} database since it seeds catalogue rows.
 */
@SpringBootTest(properties = {
    "ingestion.reconciliation-enabled=false",
    "ingestion.scheduling-enabled=false",
    "logging.level.root=WARN"
})
@Transactional
class CandidateEvaluationIntegrationTest {

    private static final OffsetDateTime EARLIER = OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime LATER = OffsetDateTime.of(2026, 9, 18, 0, 0, 0, 0, ZoneOffset.UTC);

    @Autowired private CandidateEvaluationService service;
    @Autowired private JdbcTemplate db;

    private Long userId;
    private Long ownedProductId;
    private Long userDeviceId;

    @BeforeEach
    void seedOwnedDevice() {
        assertTrue(
                db.queryForObject("SELECT current_database()", String.class).endsWith("_test"),
                "Integration tests require a dedicated database whose name ends in _test");

        db.update("DELETE FROM recommendations");
        db.update("DELETE FROM user_devices");
        db.update("DELETE FROM products");

        userId = db.queryForObject(
                """
                INSERT INTO users (email, role, password_hash)
                VALUES (?, 'USER', '{noop}unused') RETURNING id
                """,
                Long.class,
                "evaluation-" + System.nanoTime() + "@example.test");

        ownedProductId = product("Samsung", "Galaxy S22", "SMARTPHONE");
        phone(ownedProductId, 3700, 8, 60, 220, 128);
        benchmark(ownedProductId, "3200");
        price(ownedProductId, "700.00");

        userDeviceId = db.queryForObject(
                """
                INSERT INTO user_devices (user_id, product_id, custom_name)
                VALUES (?, ?, 'My phone') RETURNING id
                """,
                Long.class,
                userId,
                ownedProductId);

        db.update(
                """
                INSERT INTO device_preferences (user_device_id, budget, currency, priorities)
                VALUES (?, 1200.00, 'SGD', '{}'::jsonb)
                """,
                userDeviceId);
    }

    @Test
    void everyAffordableCandidateIsClassifiedAndRankedHighestScoreFirst() {
        Long flagship = candidate("Galaxy S25 Ultra", "1099.00", 5500, 16, 144, 180, 512, "9200");
        Long refresh = candidate("Galaxy S23", "900.00", 3750, 8, 60, 219, 128, "3300");
        Long midRange = candidate("Galaxy S24", "1000.00", 4500, 8, 120, 200, 256, "4500");

        CandidateEvaluation result = service.evaluate(userDeviceId);

        assertEquals(List.of(flagship, midRange, refresh), productIds(result.ranked()));
        assertTrue(result.skipped().isEmpty());

        double previous = Double.MAX_VALUE;
        for (CandidateEvaluation.Classified c : result.ranked()) {
            assertTrue(c.classification().upgradeScore() <= previous, "ranking must be by score, descending");
            previous = c.classification().upgradeScore();
        }
    }

    @Test
    void shortlistingStillDecidesWhatReachesTheClassifier() {
        Long affordable = candidate("Galaxy S25", "1000.00", 5000, 12, 120, 200, 256, "8000");
        candidate("Galaxy Z Fold", "2500.00", 5000, 12, 120, 200, 256, "8000");
        Long tablet = product("Samsung", "Galaxy Tab S10", "TABLET");
        phone(tablet, 8000, 12, 120, 500, 256);
        price(tablet, "900.00");

        CandidateEvaluation result = service.evaluate(userDeviceId);

        assertEquals(List.of(affordable), productIds(result.ranked()),
                "over budget, another category and the owned phone itself never reach scoring");
    }

    @Test
    void aCandidateWithNoSpecSheetIsSkippedWithoutFailingTheRun() {
        Long scored = candidate("Galaxy S25", "1000.00", 5000, 12, 120, 200, 256, "8000");
        Long noSpecs = product("Nothing", "Phone 3", "SMARTPHONE");
        price(noSpecs, "800.00");

        CandidateEvaluation result = service.evaluate(userDeviceId);

        assertEquals(List.of(scored), productIds(result.ranked()));
        assertEquals(1, result.skipped().size());
        assertEquals(noSpecs, result.skipped().get(0).candidate().getProductId());
        assertTrue(result.skipped().get(0).reason().contains("No specifications"), result.skipped().get(0).reason());
    }

    @Test
    void onlyCandidatesPastTheGateAreWorthAssessing() {
        Long flagship = candidate("Galaxy S25 Ultra", "1099.00", 5500, 16, 144, 180, 512, "9200");
        Long refresh = candidate("Galaxy S23", "900.00", 3750, 8, 60, 219, 128, "3300");

        CandidateEvaluation result = service.evaluate(userDeviceId);

        assertEquals(2, result.ranked().size());
        List<Long> worth = productIds(result.worthAssessing());
        assertTrue(worth.contains(flagship));
        assertFalse(worth.contains(refresh), "a near-identical refresh must not cost a model call");
        result.worthAssessing().forEach(c ->
                assertNotEquals(TierMapper.NO_MEANINGFUL_CHANGE, c.classification().verdict()));
    }

    @Test
    void noViableCandidatesIsAnEmptyResultNotAnError() {
        CandidateEvaluation result = service.evaluate(userDeviceId);

        assertEquals(userDeviceId, result.userDeviceId());
        assertTrue(result.ranked().isEmpty());
        assertTrue(result.skipped().isEmpty());
    }

    @Test
    void anOwnedDeviceWithNoSpecSheetFailsTheWholeRun() {
        candidate("Galaxy S25", "1000.00", 5000, 12, 120, 200, 256, "8000");
        db.update("DELETE FROM benchmark_results WHERE product_id = ?", ownedProductId);
        db.update("DELETE FROM phone WHERE product_id = ?", ownedProductId);

        var e = assertThrows(ResourceNotFoundException.class, () -> service.evaluate(userDeviceId));
        assertTrue(e.getMessage().contains("owned product"), e.getMessage());
    }

    // --- fixtures ---------------------------------------------------------

    private static List<Long> productIds(List<CandidateEvaluation.Classified> classified) {
        return classified.stream().map(c -> c.candidate().getProductId()).toList();
    }

    /** A priced, spec'd, benchmarked smartphone candidate. */
    private Long candidate(
            String modelName, String price,
            int batteryMah, int ramGb, int refreshRateHz, int weightG, int storageGb, String geekbench) {
        Long id = product("Samsung", modelName, "SMARTPHONE");
        phone(id, batteryMah, ramGb, refreshRateHz, weightG, storageGb);
        benchmark(id, geekbench);
        price(id, price);
        return id;
    }

    private Long product(String brand, String modelName, String category) {
        return db.queryForObject(
                """
                INSERT INTO products (brand, model_name, category, status, release_date)
                VALUES (?, ?, ?, 'VERIFIED', DATE '2025-02-07') RETURNING id
                """,
                Long.class,
                brand,
                modelName,
                category);
    }

    private void phone(Long productId, int batteryMah, int ramGb, int refreshRateHz, int weightG, int storageGb) {
        db.update(
                """
                INSERT INTO phone (product_id, battery_mah, ram_gb, refresh_rate_hz, weight_g, storage_gb)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                productId, batteryMah, ramGb, refreshRateHz, weightG, storageGb);
    }

    private void benchmark(Long productId, String score) {
        db.update(
                """
                INSERT INTO benchmark_results
                    (product_id, benchmark_name, score, unit, higher_is_better, source, observed_at)
                VALUES (?, 'geekbench_multi', CAST(? AS NUMERIC), 'points', true, 'test', ?)
                """,
                productId, score, LATER);
    }

    private void price(Long productId, String price) {
        db.update(
                """
                INSERT INTO price_history (product_id, price, currency, source, observed_at)
                VALUES (?, CAST(? AS NUMERIC), 'SGD', 'test', ?)
                """,
                productId, price, EARLIER);
    }
}
