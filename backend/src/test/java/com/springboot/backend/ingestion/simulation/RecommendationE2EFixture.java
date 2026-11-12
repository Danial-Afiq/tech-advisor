package com.springboot.backend.ingestion.simulation;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.springboot.backend.model.User;
import com.springboot.backend.repository.UserRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

/**
 * SCRUM-143 fixture: the base scenario from {@code docs/e2e/recommendation-e2e-expectations.md}, written
 * straight to the {@code _test} database with {@link JdbcTemplate} so no inventory trigger fires
 * during setup (the same approach as {@code SimulatedRecommendationWorkflowIntegrationTest}).
 * Every spec value here is quoted by the expectations file; change both or neither.
 */
final class RecommendationE2EFixture {

    static final String BRAND = "RECO-E2E";
    static final String OTHER_BRAND = "RECO-E2E-OTHER";
    static final String EMAIL_PREFIX = "reco-e2e-";
    static final String CAUSE = "RECO_E2E";
    static final String BASE_PRIORITIES = "{\"battery\":5}";

    static final int OWNED_BATTERY = 3700;
    static final int CANDIDATE_BATTERY = 5500;
    static final int RAM_GB = 8;
    static final int STORAGE_GB = 256;
    static final int REFRESH_HZ = 120;
    static final String DISPLAY_INCHES = "6.1";
    static final int WEIGHT_G = 190;

    private static final OffsetDateTime BASELINE_AT = OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate db;
    private final UserRepository users;
    private final ThreadPoolTaskExecutor executor;

    RecommendationE2EFixture(JdbcTemplate db, UserRepository users, ThreadPoolTaskExecutor executor) {
        this.db = db;
        this.users = users;
        this.executor = executor;
    }

    void assertTestDatabase() {
        assertTrue(db.queryForObject("SELECT current_database()", String.class).endsWith("_test"),
                "Recommendation end-to-end tests require a dedicated _test database");
    }

    static String unique() {
        return Long.toUnsignedString(System.nanoTime());
    }

    User user() {
        return users.save(new User(EMAIL_PREFIX + unique() + "@example.test", "{noop}unused", "USER"));
    }

    Long ownedPhone(String unique) {
        return phone(BRAND, "E2E Old Phone " + unique, LocalDate.of(2021, 1, 15), OWNED_BATTERY, "500.00");
    }

    Long candidatePhone(String brand, String unique, String price) {
        return phone(brand, "E2E Candidate " + unique, LocalDate.of(2025, 3, 1), CANDIDATE_BATTERY, price);
    }

    Long candidatePhone(String unique) {
        return candidatePhone(BRAND, unique, "1000.00");
    }

    private Long phone(String brand, String model, LocalDate released, int batteryMah, String price) {
        Long id = db.queryForObject("""
                INSERT INTO products (brand, model_name, category, status, release_date)
                VALUES (?, ?, 'SMARTPHONE', 'VERIFIED', ?) RETURNING id
                """, Long.class, brand, model, released);
        db.update("""
                INSERT INTO phone (product_id, battery_mah, ram_gb, storage_gb, refresh_rate_hz,
                                   display_size_inches, weight_g)
                VALUES (?, ?, ?, ?, ?, CAST(? AS NUMERIC), ?)
                """, id, batteryMah, RAM_GB, STORAGE_GB, REFRESH_HZ, DISPLAY_INCHES, WEIGHT_G);
        db.update("""
                INSERT INTO benchmark_results
                    (product_id, benchmark_name, score, unit, higher_is_better, source, observed_at)
                VALUES (?, 'geekbench_multi', 3200, 'points', true, 'reco-e2e-test', ?)
                """, id, BASELINE_AT);
        db.update("""
                INSERT INTO price_history (product_id, price, currency, source, observed_at)
                VALUES (?, CAST(? AS NUMERIC), 'SGD', 'reco-e2e-test', ?)
                """, id, price, BASELINE_AT);
        return id;
    }

    /** The owned device with its preferences; every argument is the scenario's one change. */
    Long ownedDevice(long userId, long productId, Device device) {
        Long id = db.queryForObject("""
                INSERT INTO user_devices
                    (user_id, product_id, custom_name, purchase_date, condition,
                     satisfaction_score, use_cases, is_current)
                VALUES (?, ?, 'My old phone', DATE '2021-03-01', ?, ?, '["daily use"]', true)
                RETURNING id
                """, Long.class, userId, productId, device.condition(), device.satisfaction());
        db.update("""
                INSERT INTO device_preferences
                    (user_device_id, budget, currency, upgrade_urgency, brand_flexibility, priorities, pain_points)
                VALUES (?, CAST(? AS NUMERIC), 'SGD', 'SOMEWHAT_URGENT', ?, ?::jsonb, ?::jsonb)
                """, id, device.budget(), device.flexibility(), device.priorities(), device.painPoints());
        return id;
    }

    record Device(String condition, int satisfaction, String budget, String flexibility,
                  String priorities, String painPoints) {
        static Device base() {
            return new Device("POOR", 20, "1200.00", "FLEXIBLE", BASE_PRIORITIES, "{}");
        }
        Device withPriorities(String json) { return new Device(condition, satisfaction, budget, flexibility, json, painPoints); }
        Device withFlexibility(String value) { return new Device(condition, satisfaction, budget, value, priorities, painPoints); }
        Device withOwner(String newCondition, int newSatisfaction) {
            return new Device(newCondition, newSatisfaction, budget, flexibility, priorities, painPoints);
        }
        Device withPainPoints(String json) { return new Device(condition, satisfaction, budget, flexibility, priorities, json); }
    }

    int activeRows(long deviceId, long candidateId) {
        return db.queryForObject("""
                SELECT count(*) FROM recommendations
                WHERE current_device_id = ? AND candidate_product_id = ? AND status = 'ACTIVE'
                """, Integer.class, deviceId, candidateId);
    }

    int anyRows(long deviceId, long candidateId) {
        return db.queryForObject("""
                SELECT count(*) FROM recommendations WHERE current_device_id = ? AND candidate_product_id = ?
                """, Integer.class, deviceId, candidateId);
    }

    /** The single ACTIVE row for the pair, with the JSONB columns as text. */
    Map<String, Object> activeRow(long deviceId, long candidateId) {
        return db.queryForMap("""
                SELECT id, verdict, confidence, reasoning, input_snapshot::text AS input_snapshot,
                       factor_analysis::text AS factor_analysis
                FROM recommendations
                WHERE current_device_id = ? AND candidate_product_id = ? AND status = 'ACTIVE'
                """, deviceId, candidateId);
    }

    void removeAbandonedFixture() throws InterruptedException {
        awaitIdle();
        String like = BRAND + "%";
        String emails = EMAIL_PREFIX + "%";
        db.update("DELETE FROM system_log WHERE component = 'TRIGGER_DEVICE_INVENTORY' "
                + "AND (metadata ->> 'user_device_id')::bigint IN (SELECT d.id FROM user_devices d "
                + "JOIN users u ON u.id = d.user_id WHERE u.email LIKE ?)", emails);
        db.update("DELETE FROM system_log WHERE component IN ('RECOMMENDATION_EMAIL') "
                + "AND (metadata ->> 'userId')::bigint IN (SELECT id FROM users WHERE email LIKE ?)", emails);
        db.update("DELETE FROM system_log WHERE component = 'NOTIFICATIONS' "
                + "AND (metadata ->> 'user_id')::bigint IN (SELECT id FROM users WHERE email LIKE ?)", emails);
        db.update("DELETE FROM system_log WHERE component = 'TRIGGER_MARKET_EVENT' "
                + "AND (metadata ->> 'product_id')::bigint IN (SELECT id FROM products WHERE brand LIKE ?)", like);
        db.update("DELETE FROM system_log WHERE component = 'recommendation_ai' "
                + "AND (metadata ->> 'candidate_product_id')::bigint IN (SELECT id FROM products WHERE brand LIKE ?)",
                like);
        db.update("DELETE FROM market_events WHERE product_id IN (SELECT id FROM products WHERE brand LIKE ?)", like);
        db.update("DELETE FROM users WHERE email LIKE ?", emails);
        db.update("DELETE FROM products WHERE brand LIKE ?", like);
    }

    void awaitIdle() throws InterruptedException {
        await(() -> executor.getActiveCount() == 0
                && executor.getThreadPoolExecutor().getQueue().isEmpty(), "trigger executor to drain", 20);
    }

    /** A wait that times out is a failure, never a skip. */
    static void await(BooleanSupplier condition, String description, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Timed out waiting for " + description);
    }

    /** Appends one run record as a JSON line under {@code target/reco-e2e/}. */
    static void record(String file, Map<String, ?> run) {
        try {
            Path path = Path.of("target", "reco-e2e", file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, JSON.writeValueAsString(run) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
