package com.springboot.backend.recommendation;

import static org.junit.jupiter.api.Assertions.*;

import com.springboot.backend.recommendation.classification.TierMapper;
import jakarta.persistence.EntityManager;
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
 * What a deterministic evaluation run leaves in {@code recommendations}:
 * the row contents, the absent evidence grade, supersession for
 * candidates still shortlisted, and deletion for ones that dropped out.
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
class DeterministicRecommendationPersistenceTest {

    private static final OffsetDateTime EARLIER = OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime LATER = OffsetDateTime.of(2026, 9, 18, 0, 0, 0, 0, ZoneOffset.UTC);

    @Autowired private DeterministicRecommendationService service;
    @Autowired private JdbcTemplate db;
    @Autowired private EntityManager entityManager;

    private Long userId;
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
                "persist-" + System.nanoTime() + "@example.test");

        Long owned = product("Galaxy S22");
        phone(owned, 3700, 8, 60, 220, 128);
        benchmark(owned, "3200");
        price(owned, "700.00", EARLIER);
        userDeviceId = device(owned);
    }

    @Test
    void everyClassifiedCandidateBecomesOneActiveRow() {
        Long flagship = candidate("Galaxy S25 Ultra", "1099.00", 5500, 16, 144, 180, 512, "9200");
        Long refresh = candidate("Galaxy S23", "900.00", 3750, 8, 60, 219, 128, "3300");

        DeterministicRecommendationService.PersistedEvaluation result = service.evaluateAndPersist(userDeviceId);

        assertEquals(2, result.saved());
        assertEquals(0, result.deleted());

        Map<String, Object> strong = activeRow(flagship);
        assertEquals(userId, ((Number) strong.get("user_id")).longValue());
        assertEquals(userDeviceId, ((Number) strong.get("current_device_id")).longValue());
        assertEquals(TierMapper.STRONG_UPGRADE_CANDIDATE, strong.get("verdict"));
        assertNull(strong.get("confidence"), "no model ran, so no evidence grade");
        assertNull(strong.get("trigger_event_id"));
        assertNull(strong.get("ai_model"), "no model was involved");
        assertNull(strong.get("prompt_version"));
        assertTrue(((String) strong.get("reasoning")).startsWith("Strong upgrade candidate"),
                (String) strong.get("reasoning"));

        Map<String, Object> none = activeRow(refresh);
        assertEquals(TierMapper.NO_MEANINGFUL_CHANGE, none.get("verdict"));
        assertNull(none.get("confidence"), "the gate exit is NULL too, never \"-\"");
    }

    @Test
    void theRowCarriesTheDeterministicBreakdownAndAnEmptyEvidenceHalf() {
        Long flagship = candidate("Galaxy S25 Ultra", "1099.00", 5500, 16, 144, 180, 512, "9200");

        service.evaluateAndPersist(userDeviceId);

        Map<String, Object> row = db.queryForMap(
                """
                SELECT factor_analysis -> 'deterministic' ->> 'scoring_version' AS scoring_version,
                       factor_analysis -> 'deterministic' ->> 'weighting'       AS weighting,
                       jsonb_array_length(factor_analysis -> 'evidence')      AS evidence,
                       input_snapshot -> 'analysis' ->> 'verdict'             AS snapshot_verdict,
                       input_snapshot -> 'candidate' ->> 'product_id'         AS snapshot_product
                FROM recommendations WHERE candidate_product_id = ? AND status = 'ACTIVE'
                """,
                flagship);

        assertNotNull(row.get("scoring_version"));
        assertEquals("EQUAL", row.get("weighting"));
        assertEquals(0, ((Number) row.get("evidence")).intValue());
        assertEquals(TierMapper.STRONG_UPGRADE_CANDIDATE, row.get("snapshot_verdict"));
        assertEquals(flagship.toString(), row.get("snapshot_product"));
    }

    @Test
    void aStillShortlistedCandidateIsSupersededAndKeepsItsHistory() {
        Long flagship = candidate("Galaxy S25 Ultra", "1099.00", 5500, 16, 144, 180, 512, "9200");

        service.evaluateAndPersist(userDeviceId);
        service.evaluateAndPersist(userDeviceId);

        assertEquals(2, rowCount(flagship, null));
        assertEquals(1, rowCount(flagship, "ACTIVE"));
        assertEquals(1, rowCount(flagship, "SUPERSEDED"));
    }

    @Test
    void aCandidateThatDropsOffTheShortlistIsDeletedWithItsHistory() {
        Long flagship = candidate("Galaxy S25 Ultra", "1099.00", 5500, 16, 144, 180, 512, "9200");
        Long midRange = candidate("Galaxy S24", "1000.00", 4500, 8, 120, 200, 256, "4500");
        service.evaluateAndPersist(userDeviceId);
        service.evaluateAndPersist(userDeviceId);

        // The flagship's price rises above the 1200 budget.
        price(flagship, "1399.00", LATER);
        entityManager.clear();

        DeterministicRecommendationService.PersistedEvaluation result = service.evaluateAndPersist(userDeviceId);

        assertEquals(0, rowCount(flagship, null), "no active row and no history either");
        assertEquals(2, result.deleted(), "the active row and the superseded one");
        assertEquals(1, rowCount(midRange, "ACTIVE"), "the candidate still on the shortlist is untouched");
    }

    @Test
    void anEmptyShortlistClearsEveryRowForTheDevice() {
        Long flagship = candidate("Galaxy S25 Ultra", "1099.00", 5500, 16, 144, 180, 512, "9200");
        service.evaluateAndPersist(userDeviceId);

        price(flagship, "1399.00", LATER);
        entityManager.clear();

        DeterministicRecommendationService.PersistedEvaluation result = service.evaluateAndPersist(userDeviceId);

        assertTrue(result.evaluation().ranked().isEmpty());
        assertEquals(0, result.saved());
        assertEquals(0, rowCount(flagship, null));
    }

    @Test
    void aCandidateThatCannotBeClassifiedKeepsItsPreviousRow() {
        Long flagship = candidate("Galaxy S25 Ultra", "1099.00", 5500, 16, 144, 180, 512, "9200");
        service.evaluateAndPersist(userDeviceId);

        // Still affordable and shortlisted, but its spec sheet is gone.
        db.update("DELETE FROM phone WHERE product_id = ?", flagship);
        entityManager.clear();

        DeterministicRecommendationService.PersistedEvaluation result = service.evaluateAndPersist(userDeviceId);

        assertEquals(1, result.evaluation().skipped().size());
        assertEquals(0, result.saved(), "no verdict is fabricated for it");
        assertEquals(1, rowCount(flagship, "ACTIVE"), "and the earlier answer is not thrown away");
    }

    @Test
    void anotherDevicesRowsAreNotTouched() {
        candidate("Galaxy S25", "1000.00", 5000, 12, 120, 200, 256, "8000");

        Long otherOwned = product("Pixel 8");
        phone(otherOwned, 4500, 8, 120, 190, 128);
        Long otherDevice = device(otherOwned);
        Long unrelated = product("Pixel 10");
        db.update(
                """
                INSERT INTO recommendations (user_id, current_device_id, candidate_product_id, verdict)
                VALUES (?, ?, ?, 'WORTH_WATCHING')
                """,
                userId, otherDevice, unrelated);

        service.evaluateAndPersist(userDeviceId);

        assertEquals(1, rowCount(unrelated, "ACTIVE"),
                "a candidate missing from this device's shortlist says nothing about another device");
    }

    // --- fixtures ---------------------------------------------------------

    private Map<String, Object> activeRow(Long candidateId) {
        return db.queryForMap(
                "SELECT * FROM recommendations WHERE candidate_product_id = ? AND status = 'ACTIVE'",
                candidateId);
    }

    private int rowCount(Long candidateId, String status) {
        return status == null
                ? db.queryForObject(
                        "SELECT count(*) FROM recommendations WHERE candidate_product_id = ?",
                        Integer.class, candidateId)
                : db.queryForObject(
                        "SELECT count(*) FROM recommendations WHERE candidate_product_id = ? AND status = ?",
                        Integer.class, candidateId, status);
    }

    private Long device(Long ownedProductId) {
        Long id = db.queryForObject(
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
                id);
        return id;
    }

    /** A priced, spec'd, benchmarked smartphone candidate. */
    private Long candidate(
            String modelName, String price,
            int batteryMah, int ramGb, int refreshRateHz, int weightG, int storageGb, String geekbench) {
        Long id = product(modelName);
        phone(id, batteryMah, ramGb, refreshRateHz, weightG, storageGb);
        benchmark(id, geekbench);
        price(id, price, EARLIER);
        return id;
    }

    private Long product(String modelName) {
        return db.queryForObject(
                """
                INSERT INTO products (brand, model_name, category, status, release_date)
                VALUES ('Samsung', ?, 'SMARTPHONE', 'VERIFIED', DATE '2025-02-07') RETURNING id
                """,
                Long.class,
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

    private void benchmark(Long productId, String score) {
        db.update(
                """
                INSERT INTO benchmark_results
                    (product_id, benchmark_name, score, unit, higher_is_better, source, observed_at)
                VALUES (?, 'geekbench_multi', CAST(? AS NUMERIC), 'points', true, 'test', ?)
                """,
                productId, score, LATER);
    }

    private void price(Long productId, String price, OffsetDateTime at) {
        db.update(
                """
                INSERT INTO price_history (product_id, price, currency, source, observed_at)
                VALUES (?, CAST(? AS NUMERIC), 'SGD', 'test', ?)
                """,
                productId, price, at);
    }
}
