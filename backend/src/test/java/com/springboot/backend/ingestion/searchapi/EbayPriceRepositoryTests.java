package com.springboot.backend.ingestion.searchapi;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Postgres - state()/save() are plain SQL against system_log (same pattern
 * TechApiBenchmarkRepository already uses), no JPA entity to fall back on.
 *
 * properties = "ingestion.reconciliation-enabled=false" matches the config several other
 * ingestion integration tests already use (SmartphoneCatalogSinkTests included) - CI's shared
 * Postgres service has a fixed connection budget, and a mismatched @SpringBootTest config
 * spins up a whole separate cached Spring context (and connection pool) instead of reusing
 * the one that config already pays for.
 */
@SpringBootTest(properties = "ingestion.reconciliation-enabled=false")
class EbayPriceRepositoryTests {
    @Autowired EbayPriceRepository repository;
    @Autowired JdbcTemplate db;

    @BeforeEach void reset() {
        assertTrue(db.queryForObject("SELECT current_database()", String.class).endsWith("_test"),
                "Integration tests require a dedicated database whose name ends in _test");
        db.update("DELETE FROM system_log WHERE component = 'SEARCHAPI_EBAY_PRICE_STATE'");
        db.update("DELETE FROM phone");
        db.update("DELETE FROM products");
    }

    private long product(String brand, String model) {
        return db.queryForObject(
                "INSERT INTO products (brand, model_name, category) VALUES (?, ?, 'SMARTPHONE') RETURNING id",
                Long.class, brand, model);
    }

    @Test void stateStartsAtCursorZeroWhenNoRowExistsYet() {
        assertEquals(0, repository.state().catalogueCursor);
    }

    @Test void saveThenStateRoundTripsTheExactCursor() {
        var state = new EbayPriceIngestionState();
        state.catalogueCursor = 42;
        repository.save(state);
        assertEquals(42, repository.state().catalogueCursor);
    }

    @Test void savingTwiceUpsertsRatherThanInsertingASecondRow() {
        var first = new EbayPriceIngestionState();
        first.catalogueCursor = 1;
        repository.save(first);
        var second = new EbayPriceIngestionState();
        second.catalogueCursor = 2;
        repository.save(second);
        assertEquals(2, repository.state().catalogueCursor);
        assertEquals(1, db.queryForObject(
                "SELECT count(*) FROM system_log WHERE component = 'SEARCHAPI_EBAY_PRICE_STATE'", Integer.class));
    }

    @Test void nextProductsReturnsUpToLimitAfterTheCursorWithoutWrapping() {
        long a = product("Apple", "iPhone 16");
        long b = product("Apple", "iPhone 16 Pro");
        product("Samsung", "Galaxy S25"); // not reached - limit 2

        var batch = repository.nextProducts(0, 2);
        assertFalse(batch.wrapped());
        assertEquals(List.of(a, b), batch.products().stream().map(EbayPriceRepository.Product::id).toList());
    }

    @Test void nextProductsWrapsToTheStartWhenFewerThanLimitRemain() {
        long a = product("Apple", "iPhone 16");
        long b = product("Apple", "iPhone 16 Pro");
        long c = product("Samsung", "Galaxy S25");

        // Cursor at b: only c remains forward - wraps to pick up a too, filling the batch of 3.
        var batch = repository.nextProducts(b, 3);
        assertTrue(batch.wrapped());
        assertEquals(List.of(c, a), batch.products().stream().map(EbayPriceRepository.Product::id).toList());
    }

    @Test void nextProductsExcludesNonVerifiedOrNonSmartphoneProducts() {
        long verified = product("Apple", "iPhone 16");
        db.update("INSERT INTO products (brand, model_name, category, status) VALUES ('Nvidia', 'RTX 5090', 'GPU', 'VERIFIED')");
        db.update("INSERT INTO products (brand, model_name, category, status) VALUES ('Pending', 'Phone', 'SMARTPHONE', 'PENDING')");

        var batch = repository.nextProducts(0, 10);
        assertEquals(List.of(verified), batch.products().stream().map(EbayPriceRepository.Product::id).toList());
    }

    @Test void nextProductsReturnsEverythingWithoutWrappingWhenTheCatalogueIsSmallerThanTheLimit() {
        long a = product("Apple", "iPhone 16");
        var batch = repository.nextProducts(0, 10);
        assertTrue(batch.wrapped(), "a short catalogue is still a complete sweep in one run");
        assertEquals(List.of(a), batch.products().stream().map(EbayPriceRepository.Product::id).toList());
    }
}
