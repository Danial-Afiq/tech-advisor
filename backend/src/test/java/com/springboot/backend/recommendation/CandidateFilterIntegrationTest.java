package com.springboot.backend.recommendation;

import com.springboot.backend.repository.CandidateProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the shortlisting query against real PostgreSQL, because the parts
 * most worth testing - {@code DISTINCT ON}, the inner join, numeric comparison
 * - are the parts an in-memory stand-in would not reproduce faithfully.
 *
 * <p>Runs inside a transaction that rolls back, and refuses to run at all
 * outside a dedicated {@code *_test} database, since it seeds catalogue rows.
 */
@SpringBootTest(properties = {
        "ingestion.reconciliation-enabled=false",
        "ingestion.scheduling-enabled=false",
        "logging.level.root=WARN"})
@Transactional
class CandidateFilterIntegrationTest {

    private static final OffsetDateTime EARLIER =
            OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime LATER =
            OffsetDateTime.of(2026, 9, 18, 0, 0, 0, 0, ZoneOffset.UTC);

    @Autowired private CandidatePruningService service;
    @Autowired private JdbcTemplate db;

    private Long ownedProductId;
    private Long userDeviceId;

    @BeforeEach
    void seedOwnedDevice() {
        assertTrue(db.queryForObject("SELECT current_database()", String.class).endsWith("_test"),
                "Integration tests require a dedicated database whose name ends in _test");

        // Start from an empty catalogue so a neighbouring test's committed rows
        // cannot collide with the fixtures below. These deletes roll back with
        // the rest of the test, leaving the database as it was found.
        db.update("DELETE FROM user_devices");
        db.update("DELETE FROM products");

        Long userId = db.queryForObject("""
                INSERT INTO users (email, role, password_hash)
                VALUES (?, 'USER', '{noop}unused') RETURNING id
                """, Long.class, "candidate-filter-" + System.nanoTime() + "@example.test");

        ownedProductId = product("Samsung", "Galaxy S22", "SMARTPHONE", "VERIFIED");
        price(ownedProductId, "700.00", EARLIER);

        userDeviceId = db.queryForObject("""
                INSERT INTO user_devices (user_id, product_id, custom_name)
                VALUES (?, ?, 'My phone') RETURNING id
                """, Long.class, userId, ownedProductId);

        db.update("""
                INSERT INTO device_preferences (user_device_id, budget, currency)
                VALUES (?, 1000.00, 'SGD')
                """, userDeviceId);
    }

    @Test
    void excludesCandidatesInAnotherCategory() {
        Long laptop = product("Apple", "MacBook Air M4", "LAPTOP", "VERIFIED");
        price(laptop, "950.00", LATER);
        Long phone = product("Google", "Pixel 10", "SMARTPHONE", "VERIFIED");
        price(phone, "950.00", LATER);

        assertEquals(List.of(phone), productIds(service.getViableCandidates(userDeviceId)),
                "A laptop is never an upgrade path for a phone, however affordable");
    }

    @Test
    void excludesCandidatesAboveTheBudgetCeiling() {
        Long affordable = product("Google", "Pixel 10", "SMARTPHONE", "VERIFIED");
        price(affordable, "950.00", LATER);
        Long tooExpensive = product("Apple", "iPhone 17 Pro", "SMARTPHONE", "VERIFIED");
        price(tooExpensive, "1050.00", LATER);

        assertEquals(List.of(affordable), productIds(service.getViableCandidates(userDeviceId)));
    }

    @Test
    void includesACandidatePricedExactlyAtTheBudget() {
        // The ceiling is inclusive: a phone at exactly the stated budget is
        // affordable, and excluding it would quietly narrow every user's set.
        Long exact = product("Google", "Pixel 10", "SMARTPHONE", "VERIFIED");
        price(exact, "1000.00", LATER);

        assertEquals(List.of(exact), productIds(service.getViableCandidates(userDeviceId)));
    }

    @Test
    void judgesAffordabilityOnTheLatestPriceNotAnyPastOne() {
        // The motivating case: a phone that was out of reach yesterday and is
        // not today. If the filter saw the old price, the drop would go unnoticed.
        Long dropped = product("Apple", "iPhone 17", "SMARTPHONE", "VERIFIED");
        price(dropped, "1200.00", EARLIER);
        price(dropped, "950.00", LATER);

        List<CandidateProduct> candidates = service.getViableCandidates(userDeviceId);

        assertEquals(List.of(dropped), productIds(candidates));
        assertEquals(0, new BigDecimal("950.00").compareTo(candidates.getFirst().getLatestPrice()));
    }

    @Test
    void usesTheLatestPriceEvenWhenTwoObservationsShareATimestamp() {
        // Same observed_at from two sources. The id tiebreaker is what makes
        // the answer reproducible instead of whichever row the planner returns.
        Long contested = product("Google", "Pixel 10", "SMARTPHONE", "VERIFIED");
        price(contested, "1400.00", LATER);
        price(contested, "900.00", LATER);

        assertEquals(List.of(contested), productIds(service.getViableCandidates(userDeviceId)));
    }

    @Test
    void excludesTheProductTheUserAlreadyOwns() {
        price(ownedProductId, "500.00", LATER);

        assertTrue(service.getViableCandidates(userDeviceId).isEmpty(),
                "Recommending the phone they are holding is never an upgrade");
    }

    @Test
    void excludesAProductWithNoPriceHistory() {
        // Affordability cannot be verified, so it cannot clear a budget gate.
        product("Google", "Pixel 10", "SMARTPHONE", "VERIFIED");

        assertTrue(service.getViableCandidates(userDeviceId).isEmpty());
    }

    @Test
    void excludesAProductWhoseIdentityIngestionCouldNotResolve() {
        Long quarantined = product("UNKNOWN", "demo phone", "SMARTPHONE", "UNVERIFIED");
        price(quarantined, "600.00", LATER);

        assertTrue(service.getViableCandidates(userDeviceId).isEmpty(),
                "A product we could not identify must not reach a user");
    }


    @Test
    void ordersCandidatesByPrice() {
        Long dearer = product("Apple", "iPhone 17", "SMARTPHONE", "VERIFIED");
        price(dearer, "990.00", LATER);
        Long cheaper = product("Google", "Pixel 10", "SMARTPHONE", "VERIFIED");
        price(cheaper, "810.00", LATER);

        assertEquals(List.of(cheaper, dearer), productIds(service.getViableCandidates(userDeviceId)));
    }

    private Long product(String brand, String model, String category, String status) {
        return db.queryForObject("""
                INSERT INTO products (brand, model_name, category, status)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, brand, model, category, status);
    }

    private void price(Long productId, String amount, OffsetDateTime observedAt) {
        db.update("""
                INSERT INTO price_history (product_id, price, currency, source, observed_at)
                VALUES (?, ?, 'SGD', 'test', ?)
                """, productId, new BigDecimal(amount), observedAt);
    }

    private static List<Long> productIds(List<CandidateProduct> candidates) {
        return candidates.stream().map(CandidateProduct::getProductId).toList();
    }
}
