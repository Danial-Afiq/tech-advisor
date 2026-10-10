package com.springboot.backend.recommendation.trigger;

import static org.junit.jupiter.api.Assertions.*;

import com.springboot.backend.dto.DeviceRequest;
import com.springboot.backend.dto.DeviceResponse;
import com.springboot.backend.service.DeviceService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Adding a device through {@link DeviceService} leads to recommendation rows
 * without anyone calling the pipeline: the after-commit listener and the async
 * executor are really wired.
 *
 * <p>Deliberately <b>not</b> {@code @Transactional}. The trigger only fires
 * after a commit, so a rolled-back test would never see it. Everything this
 * test commits is removed again afterwards.
 */
@SpringBootTest(properties = {
    "ingestion.reconciliation-enabled=false",
    "ingestion.scheduling-enabled=false",
    "logging.level.root=WARN"
})
class InventoryRecommendationTriggerIntegrationTest {

    private static final OffsetDateTime OBSERVED = OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    @Autowired private DeviceService deviceService;
    @Autowired private JdbcTemplate db;

    private String email;
    private Long ownedProductId;

    @BeforeEach
    void seedCatalogue() {
        assertTrue(
                db.queryForObject("SELECT current_database()", String.class).endsWith("_test"),
                "Integration tests require a dedicated database whose name ends in _test");

        cleanUp();

        email = "trigger-" + System.nanoTime() + "@example.test";
        db.update("INSERT INTO users (email, role, password_hash) VALUES (?, 'USER', '{noop}unused')", email);

        ownedProductId = phone("Galaxy S22", "700.00", 3700, 8, 60, 220, 128, "3200");
        phone("Galaxy S25 Ultra", "1099.00", 5500, 16, 144, 180, 512, "9200");
    }

    @AfterEach
    void cleanUp() {
        db.update("DELETE FROM recommendations");
        db.update("DELETE FROM user_devices");
        db.update("DELETE FROM products");
        db.update("DELETE FROM users WHERE email LIKE 'trigger-%@example.test'");
    }

    @Test
    void addingADeviceWithABudgetProducesRecommendations() throws InterruptedException {
        DeviceRequest request = new DeviceRequest();
        request.setProductId(ownedProductId);
        request.setBudget(new BigDecimal("1200.00"));

        DeviceResponse added = deviceService.createDevice(email, request);

        assertTrue(awaitRows(added.getId(), 1), "the new device's verdicts were never written");
    }

    @Test
    void addingADeviceWithoutABudgetProducesNothing() throws InterruptedException {
        DeviceRequest request = new DeviceRequest();
        request.setProductId(ownedProductId);

        DeviceResponse added = deviceService.createDevice(email, request);

        assertFalse(awaitRows(added.getId(), 1), "a device with no budget cannot be shortlisted against");
    }

    /** Polls for up to five seconds, since the evaluation runs on another thread. */
    private boolean awaitRows(Long deviceId, int expected) throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            Integer rows = db.queryForObject(
                    "SELECT count(*) FROM recommendations WHERE current_device_id = ?", Integer.class, deviceId);
            if (rows >= expected) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
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
                Long.class,
                modelName);
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
