package com.springboot.backend.recommendation.classification;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The classifier against real PostgreSQL, using representative product pairs
 * with expected tiers.
 *
 * <p>Complements the unit tests: what only a database can exercise is the
 * {@code DISTINCT ON} benchmark selection, the JSONB
 * {@code spec_overrides} column, and the wiring of the configured thresholds
 * through the real Spring context.
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
class UpgradeClassificationIntegrationTest {

    private static final OffsetDateTime EARLIER = OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime LATER = OffsetDateTime.of(2026, 9, 18, 0, 0, 0, 0, ZoneOffset.UTC);

    @Autowired private UpgradeClassificationService service;
    @Autowired private ScoringSettings settings;
    @Autowired private JdbcTemplate db;
    @Autowired private EntityManager entityManager;

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
                "classification-" + System.nanoTime() + "@example.test");

        // A tired mid-range phone: modest battery, slow display, heavy.
        ownedProductId = product("Samsung", "Galaxy S22");
        phone(ownedProductId, 3700, 8, 60, 220, 128);
        benchmark(ownedProductId, "geekbench_multi", "3200", true, EARLIER);
        price(ownedProductId, "700.00", EARLIER);

        userDeviceId = db.queryForObject(
                """
                INSERT INTO user_devices (user_id, product_id, custom_name)
                VALUES (?, ?, 'My phone') RETURNING id
                """,
                Long.class,
                userId,
                ownedProductId);

        preferences("1200.00", """
                {"battery": 5, "performance": 4, "display": 4, "portability": 3}
                """);
    }

    @Test
    void aFlagshipUpgradeAcrossEveryMeasuredFactorIsAStrongCandidate() {
        Long candidate = product("Samsung", "Galaxy S25 Ultra");
        phone(candidate, 5500, 16, 144, 180, 512);
        benchmark(candidate, "geekbench_multi", "9200", true, LATER);

        UpgradeClassification result =
                service.classify(userDeviceId, candidate, new BigDecimal("1099.00"));

        assertEquals(TierMapper.STRONG_UPGRADE_CANDIDATE, result.verdict());
        assertTrue(result.upgradeScore() >= settings.strongThreshold());
        assertFalse(result.isInsufficientSpecCoverage());
        assertEquals(settings.scoringVersion(), result.scoringVersion());
    }

    @Test
    void thisYearsNearIdenticalRefreshIsNoMeaningfulChange() {
        Long candidate = product("Samsung", "Galaxy S23");
        phone(candidate, 3750, 8, 60, 219, 128);
        benchmark(candidate, "geekbench_multi", "3300", true, LATER);

        UpgradeClassification result =
                service.classify(userDeviceId, candidate, new BigDecimal("900.00"));

        assertEquals(TierMapper.NO_MEANINGFUL_CHANGE, result.verdict());
        assertFalse(result.isInsufficientSpecCoverage(), "the specs were knowable, they just barely moved");
    }

    @Test
    void theLatestBenchmarkObservationWins() {
        Long candidate = product("Samsung", "Galaxy S25");
        phone(candidate, 5000, 12, 120, 200, 256);
        // A stale, wrong reading followed by the real one. Only the latest counts.
        benchmark(candidate, "geekbench_multi", "100", true, EARLIER);
        benchmark(candidate, "geekbench_multi", "8000", true, LATER);

        UpgradeClassification result =
                service.classify(userDeviceId, candidate, new BigDecimal("1000.00"));

        assertTrue(
                result.comparison().benchmarkUpliftPct() > 100.0,
                "uplift was " + result.comparison().benchmarkUpliftPct() + "; the stale row must be ignored");
    }

    @Test
    void specOverridesFromTheJsonbColumnChangeTheOutcome() {
        Long candidate = product("Samsung", "Galaxy S25");
        phone(candidate, 5000, 12, 120, 200, 256);
        benchmark(candidate, "geekbench_multi", "8000", true, LATER);

        double withCatalogueSpecs =
                service.classify(userDeviceId, candidate, new BigDecimal("1000.00")).upgradeScore();

        // This owner actually bought the 1TB model, so the storage jump reverses.
        db.update("UPDATE user_devices SET spec_overrides = ?::jsonb WHERE id = ?",
                "{\"storage_gb\": 1024}", userDeviceId);
        // The update bypassed JPA, and this test's single transaction still holds
        // the UserDevice the first classify loaded. Without clearing, the second
        // classify is handed that stale entity and never sees the override.
        entityManager.clear();

        double withOverride =
                service.classify(userDeviceId, candidate, new BigDecimal("1000.00")).upgradeScore();

        assertTrue(
                withOverride < withCatalogueSpecs,
                "downgrading storage should score lower: " + withOverride + " vs " + withCatalogueSpecs);
    }

    @Test
    void aCandidateWithNoSpecSheetFailsClearlyRatherThanScoringZero() {
        Long candidate = product("Nothing", "Phone 3");
        // No phone row at all.

        var e = assertThrows(
                com.springboot.backend.exception.ResourceNotFoundException.class,
                () -> service.classify(userDeviceId, candidate, new BigDecimal("500.00")));
        assertTrue(e.getMessage().contains("No specifications"), e.getMessage());
    }

    @Test
    void anAlmostEmptySpecSheetIsInsufficientDataNotNoChange() {
        // Only battery is knowable on both sides, out of every scorable factor.
        Long candidate = product("Nothing", "Phone 3");
        db.update("INSERT INTO phone (product_id, battery_mah) VALUES (?, ?)", candidate, 5000);

        UpgradeClassification result =
                service.classify(userDeviceId, candidate, new BigDecimal("500.00"));

        assertTrue(result.isInsufficientSpecCoverage());
        // Reported as the lowest tier, but the breakdown says why.
        assertEquals(TierMapper.NO_MEANINGFUL_CHANGE, result.verdict());
        Map<String, Object> factors = result.toDeterministicFactors();
        assertEquals(false, factors.get("sufficient_data"));
        assertTrue(factors.containsKey("coverage"));
    }

    @Test
    void theDeterministicBreakdownCarriesEnoughToAuditTheScore() {
        Long candidate = product("Samsung", "Galaxy S25 Ultra");
        phone(candidate, 5500, 16, 144, 180, 512);
        benchmark(candidate, "geekbench_multi", "9200", true, LATER);

        Map<String, Object> factors = service
                .classify(userDeviceId, candidate, new BigDecimal("1099.00"))
                .toDeterministicFactors();

        assertEquals(settings.scoringVersion(), factors.get("scoring_version"));
        assertTrue(factors.containsKey("upgrade_score"));
        assertTrue(factors.containsKey("unmeasurable_factors"), "must say what nothing can measure");

        @SuppressWarnings("unchecked")
        Map<String, Object> perFactor = (Map<String, Object>) factors.get("factors");
        assertTrue(perFactor.containsKey(Factors.BATTERY));

        @SuppressWarnings("unchecked")
        Map<String, Object> battery = (Map<String, Object>) perFactor.get(Factors.BATTERY);
        assertFalse(battery.containsKey("priority"), "user priorities do not weight the verdict yet (§27.5)");
        assertEquals("EQUAL", factors.get("weighting"));
        assertEquals("HIGH_POSITIVE", battery.get("impact"));
    }

    // --- fixtures ---------------------------------------------------------

    private Long product(String brand, String modelName) {
        return db.queryForObject(
                """
                INSERT INTO products (brand, model_name, category, status, release_date)
                VALUES (?, ?, 'SMARTPHONE', 'VERIFIED', DATE '2025-02-07') RETURNING id
                """,
                Long.class,
                brand,
                modelName);
    }

    private void phone(Long productId, int batteryMah, int ramGb, int refreshRateHz, int weightG, int storageGb) {
        db.update(
                """
                INSERT INTO phone (product_id, battery_mah, ram_gb, refresh_rate_hz, weight_g, storage_gb)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                productId, batteryMah, ramGb, refreshRateHz, weightG, storageGb);
    }

    private void benchmark(Long productId, String name, String score, boolean higherIsBetter, OffsetDateTime at) {
        db.update(
                """
                INSERT INTO benchmark_results
                    (product_id, benchmark_name, score, unit, higher_is_better, source, observed_at)
                VALUES (?, ?, CAST(? AS NUMERIC), 'points', ?, 'test', ?)
                """,
                productId, name, score, higherIsBetter, at);
    }

    private void price(Long productId, String price, OffsetDateTime at) {
        db.update(
                """
                INSERT INTO price_history (product_id, price, currency, source, observed_at)
                VALUES (?, CAST(? AS NUMERIC), 'SGD', 'test', ?)
                """,
                productId, price, at);
    }

    private void preferences(String budget, String prioritiesJson) {
        db.update(
                """
                INSERT INTO device_preferences (user_device_id, budget, currency, priorities)
                VALUES (?, CAST(? AS NUMERIC), 'SGD', ?::jsonb)
                """,
                userDeviceId, budget, prioritiesJson);
    }
}
