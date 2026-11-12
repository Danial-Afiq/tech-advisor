package com.springboot.backend.ingestion.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.springboot.backend.marketevent.MarketEvent;
import com.springboot.backend.marketevent.MarketEventService;
import com.springboot.backend.marketevent.MarketEventType;
import com.springboot.backend.repository.BenchmarkResultRepository;
import com.springboot.backend.repository.ProductRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "ingestion.reconciliation-enabled=false",
        "ingestion.scheduling-enabled=false",
        "recommendation.triggers.ai-assessment-enabled=false"
})
@ActiveProfiles("ingestion-demo")
class SimulatedMarketChangeIntegrationTest {

    private static final String BRAND = "SimulatedMarketChangeBatch2Test";
    private static final Instant BASELINE_AT = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired SimulatedMarketChangeService changes;
    @Autowired MarketEventService marketEvents;
    @Autowired ProductRepository products;
    @Autowired BenchmarkResultRepository benchmarks;
    @Autowired JdbcTemplate db;

    private long productId;

    @BeforeEach
    void reset() {
        assertTrue(db.queryForObject("SELECT current_database()", String.class).endsWith("_test"),
                "Integration tests require a dedicated database whose name ends in _test");
        db.update("""
                DELETE FROM market_events
                WHERE product_id IN (SELECT id FROM products WHERE brand = ?)
                """, BRAND);
        db.update("DELETE FROM products WHERE brand = ?", BRAND);

        productId = db.queryForObject("""
                INSERT INTO products(brand, model_name, category, status)
                VALUES (?, 'Phone', 'SMARTPHONE', 'VERIFIED') RETURNING id
                """, Long.class, BRAND);
        db.update("INSERT INTO phone(product_id, chipset, battery_mah) VALUES (?, 'Demo Chip', 5000)", productId);
        db.update("""
                INSERT INTO price_history(product_id, price, currency, source, observed_at)
                VALUES (?, 999.00, 'SGD', 'real-price', ?)
                """, productId, OffsetDateTime.ofInstant(BASELINE_AT, ZoneOffset.UTC));
        db.update("""
                INSERT INTO benchmark_results
                    (product_id, benchmark_name, score, unit, higher_is_better, source, observed_at)
                VALUES (?, 'Geekbench 6 Multi-Core', 8000, 'points', true, 'real-benchmark', ?)
                """, productId, OffsetDateTime.ofInstant(BASELINE_AT, ZoneOffset.UTC));
    }

    @Test
    void priceChangeIsLatestAtomicAndExactlyOnceAcrossRetries() throws InterruptedException {
        var command = new SimulatedMarketChangeService.PriceCommand(
                productId, new BigDecimal("899.00"), "price-drop-demo-1");

        var first = changes.injectPrice(command);
        assertFalse(first.alreadyApplied());
        assertEquals(MarketEventType.PRICE_CHANGE, first.eventType());
        assertEquals(0, new BigDecimal("899.00").compareTo(
                products.findLatestPrice(productId).orElseThrow().getPrice()));
        assertEquals(first.source(), latestSource("price_history", null));
        assertEventValues(first.marketEventId(), "price", "999.00", "899.00");

        var retry = changes.injectPrice(command);
        assertTrue(retry.alreadyApplied());
        assertEquals(first.marketEventId(), retry.marketEventId());
        assertEquals(1, observationCount("price_history", first.source()));
        assertEquals(1, eventCount(first.source()));
        awaitSingleTrigger(first.marketEventId());

        assertThrows(IllegalArgumentException.class, () -> changes.injectPrice(
                new SimulatedMarketChangeService.PriceCommand(
                        productId, new BigDecimal("850.00"), "price-drop-demo-1")));
        assertEquals(1, observationCount("price_history", first.source()));
        assertEquals(1, eventCount(first.source()));
    }

    @Test
    void benchmarkChangePreservesMetadataBecomesLatestAndTriggersOnce() throws InterruptedException {
        var command = new SimulatedMarketChangeService.BenchmarkCommand(
                productId, "Geekbench 6 Multi-Core", new BigDecimal("8500"), "benchmark-demo-1");

        var first = changes.injectBenchmark(command);
        assertFalse(first.alreadyApplied());
        assertEquals(MarketEventType.BENCHMARK_UPDATE, first.eventType());
        var latest = benchmarks.findFirstByProductIdAndBenchmarkNameOrderByObservedAtDescIdDesc(
                productId, "Geekbench 6 Multi-Core").orElseThrow();
        assertEquals(0, new BigDecimal("8500").compareTo(latest.getScore()));
        assertEquals("points", latest.getUnit());
        assertTrue(latest.isHigherIsBetter());
        assertEquals(first.source(), latest.getSource());
        assertEventValues(first.marketEventId(), "score", "8000", "8500");

        var retry = changes.injectBenchmark(command);
        assertTrue(retry.alreadyApplied());
        assertEquals(first.marketEventId(), retry.marketEventId());
        assertEquals(1, observationCount("benchmark_results", first.source()));
        assertEquals(1, eventCount(first.source()));
        awaitSingleTrigger(first.marketEventId());
    }

    private void assertEventValues(long eventId, String key, String oldValue, String newValue) {
        MarketEvent event = marketEvents.find(eventId).orElseThrow();
        assertEquals(0, new BigDecimal(oldValue).compareTo(decimal(event.oldValue(), key)));
        assertEquals(0, new BigDecimal(newValue).compareTo(decimal(event.newValue(), key)));
        assertEquals(productId, event.productId());
    }

    private static BigDecimal decimal(Map<String, Object> values, String key) {
        return new BigDecimal(values.get(key).toString());
    }

    private String latestSource(String table, String benchmarkName) {
        if (benchmarkName == null) {
            return db.queryForObject(
                    "SELECT source FROM " + table
                            + " WHERE product_id = ? ORDER BY observed_at DESC, id DESC LIMIT 1",
                    String.class, productId);
        }
        return db.queryForObject(
                "SELECT source FROM " + table
                        + " WHERE product_id = ? AND benchmark_name = ? ORDER BY observed_at DESC, id DESC LIMIT 1",
                String.class, productId, benchmarkName);
    }

    private int observationCount(String table, String source) {
        return db.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE product_id = ? AND source = ?",
                Integer.class, productId, source);
    }

    private int eventCount(String source) {
        return db.queryForObject(
                "SELECT count(*) FROM market_events WHERE product_id = ? AND source = ?",
                Integer.class, productId, source);
    }

    private void awaitSingleTrigger(long eventId) throws InterruptedException {
        String countSql = """
                SELECT count(*) FROM system_log
                WHERE component = 'TRIGGER_MARKET_EVENT'
                  AND (metadata ->> 'market_event_id')::bigint = ?
                """;
        await(() -> db.queryForObject(countSql, Integer.class, eventId) == 1);
        Thread.sleep(200);
        assertEquals("MARKET_EVENT_RECORDED", db.queryForObject("""
                SELECT metadata ->> 'cause' FROM system_log
                WHERE component = 'TRIGGER_MARKET_EVENT'
                  AND (metadata ->> 'market_event_id')::bigint = ?
                """, String.class, eventId));
        assertEquals(1, db.queryForObject(countSql, Integer.class, eventId));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), "Timed out waiting for the asynchronous recommendation trigger");
    }
}
