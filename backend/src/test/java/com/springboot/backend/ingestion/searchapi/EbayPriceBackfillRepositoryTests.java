package com.springboot.backend.ingestion.searchapi;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Postgres - same system_log-row state pattern as EbayPriceRepositoryTests, but this
 * repository's nextProducts() is a bounded, non-wrapping, brand-scoped one-time sweep rather
 * than an infinite round-robin, so its test shape differs where that matters.
 */
@SpringBootTest(properties = "ingestion.reconciliation-enabled=false")
class EbayPriceBackfillRepositoryTests {
    private static final List<String> POPULAR = List.of("Apple", "Samsung");

    @Autowired EbayPriceBackfillRepository repository;
    @Autowired JdbcTemplate db;

    @BeforeEach void reset() {
        assertTrue(db.queryForObject("SELECT current_database()", String.class).endsWith("_test"),
                "Integration tests require a dedicated database whose name ends in _test");
        db.update("DELETE FROM system_log WHERE component = 'SEARCHAPI_EBAY_BACKFILL_STATE'");
        db.update("DELETE FROM price_history");
        db.update("DELETE FROM phone");
        db.update("DELETE FROM gpu");
        db.update("DELETE FROM products");
    }

    private long product(String brand, String model, String category) {
        return db.queryForObject(
                "INSERT INTO products (brand, model_name, category) VALUES (?, ?, ?) RETURNING id",
                Long.class, brand, model, category);
    }

    private void priced(long productId, String source) {
        db.update("""
                INSERT INTO price_history (product_id, price, currency, source, observed_at)
                VALUES (?, 1.00, 'USD', ?, CURRENT_TIMESTAMP)
                """, productId, source);
    }

    @Test void stateStartsAtCursorZeroNotCompletedWhenNoRowExistsYet() {
        var state = repository.state();
        assertEquals(0, state.cursor);
        assertFalse(state.completed);
    }

    @Test void saveThenStateRoundTripsExactlyAndUpserts() {
        var first = new EbayPriceBackfillState();
        first.cursor = 7; first.completed = false;
        repository.save(first);
        assertEquals(7, repository.state().cursor);

        var second = new EbayPriceBackfillState();
        second.cursor = 9; second.completed = true;
        repository.save(second);
        assertEquals(9, repository.state().cursor);
        assertTrue(repository.state().completed);
        assertEquals(1, db.queryForObject(
                "SELECT count(*) FROM system_log WHERE component = 'SEARCHAPI_EBAY_BACKFILL_STATE'", Integer.class));
    }

    @Test void nextProductsIncludesEveryGpuRegardlessOfBrand() {
        long gpu = product("Nvidia", "RTX 5090", "GPU");
        var batch = repository.nextProducts(0, 10, POPULAR);
        assertEquals(List.of(gpu), batch.products().stream().map(EbayPriceRepository.Product::id).toList());
    }

    @Test void nextProductsOnlyIncludesSmartphonesInTheBrandList() {
        long apple = product("Apple", "iPhone 16", "SMARTPHONE");
        product("Doogee", "S100", "SMARTPHONE"); // excluded - not in POPULAR

        var batch = repository.nextProducts(0, 10, POPULAR);
        assertEquals(List.of(apple), batch.products().stream().map(EbayPriceRepository.Product::id).toList());
    }

    @Test void nextProductsExcludesProductsAlreadyPricedByEitherEbaySource() {
        long alreadyByDaily = product("Apple", "iPhone 16", "SMARTPHONE");
        priced(alreadyByDaily, EbayPriceSource.ID);
        long alreadyByBackfill = product("Samsung", "Galaxy S25", "SMARTPHONE");
        priced(alreadyByBackfill, EbayPriceBackfillSource.ID);
        long notYetPriced = product("Nvidia", "RTX 5090", "GPU");

        var batch = repository.nextProducts(0, 10, POPULAR);
        assertEquals(List.of(notYetPriced), batch.products().stream().map(EbayPriceRepository.Product::id).toList());
    }

    @Test void nextProductsExcludesNonVerifiedProducts() {
        db.update("INSERT INTO products (brand, model_name, category, status) VALUES ('Apple', 'iPhone 16', 'SMARTPHONE', 'PENDING')");
        var batch = repository.nextProducts(0, 10, POPULAR);
        assertEquals(List.of(), batch.products());
    }

    @Test void nextProductsNeverWraps() {
        // Unlike EbayPriceRepository's round-robin: fewer than the limit remain means the
        // backfill is exhausted, not "go back to the start".
        long only = product("Nvidia", "RTX 5090", "GPU");
        var batch = repository.nextProducts(0, 10, POPULAR);
        assertTrue(batch.exhausted());
        assertEquals(List.of(only), batch.products().stream().map(EbayPriceRepository.Product::id).toList());
    }

    @Test void nextProductsIsNotExhaustedWhenExactlyTheLimitRemains() {
        long a = product("Nvidia", "RTX 5090", "GPU");
        product("Nvidia", "RTX 4090", "GPU"); // not reached - limit 1
        var batch = repository.nextProducts(0, 1, POPULAR);
        assertFalse(batch.exhausted());
        assertEquals(List.of(a), batch.products().stream().map(EbayPriceRepository.Product::id).toList());
    }

    @Test void targetCountAndAttemptedCountReflectScopeAndCursorPosition() {
        long apple = product("Apple", "iPhone 16", "SMARTPHONE");
        product("Doogee", "S100", "SMARTPHONE"); // not in scope at all
        long gpu = product("Nvidia", "RTX 5090", "GPU");

        assertEquals(2, repository.targetCount(POPULAR)); // apple + gpu, not doogee
        assertEquals(0, repository.attemptedCount(0, POPULAR));
        assertEquals(1, repository.attemptedCount(apple, POPULAR));
        assertEquals(2, repository.attemptedCount(gpu, POPULAR));
    }

    @Test void observationsCountOnlyCountsThisSourcesOwnRows() {
        long a = product("Apple", "iPhone 16", "SMARTPHONE");
        long b = product("Samsung", "Galaxy S25", "SMARTPHONE");
        priced(a, EbayPriceBackfillSource.ID);
        priced(b, EbayPriceSource.ID); // the daily source's row, not this one's
        assertEquals(1, repository.observationsCount());
    }
}
