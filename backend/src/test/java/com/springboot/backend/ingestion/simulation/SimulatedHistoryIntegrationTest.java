package com.springboot.backend.ingestion.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.springboot.backend.repository.BenchmarkResultRepository;
import com.springboot.backend.repository.ProductRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "ingestion.reconciliation-enabled=false",
        "ingestion.scheduling-enabled=false"
})
@ActiveProfiles("ingestion-demo")
class SimulatedHistoryIntegrationTest {

    private static final String BRAND = "SimulatedHistoryBatch1Test";
    private static final Instant CURRENT_AT = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired SimulatedHistoryService service;
    @Autowired JdbcTemplate db;
    @Autowired ProductRepository products;
    @Autowired BenchmarkResultRepository benchmarks;

    private long phoneId;
    private long gpuId;

    @BeforeEach
    void reset() {
        assertTrue(db.queryForObject("SELECT current_database()", String.class).endsWith("_test"),
                "Integration tests require a dedicated database whose name ends in _test");
        db.update("DELETE FROM products WHERE brand = ?", BRAND);

        phoneId = product("Phone", "SMARTPHONE");
        db.update("""
                INSERT INTO phone(product_id, chipset, ram_gb, storage_gb, battery_mah)
                VALUES (?, 'Demo Chip', 12, 256, 5000)
                """, phoneId);
        price(phoneId, "999.00", "phone-real-price", CURRENT_AT);
        benchmark(phoneId, "Geekbench 6 Multi-Core", "8000", "points", true,
                "phone-real-benchmark", CURRENT_AT);
        benchmark(phoneId, "Render latency", "25", "ms", false,
                "phone-real-benchmark", CURRENT_AT);

        gpuId = product("GPU", "GPU");
        db.update("""
                INSERT INTO gpu(product_id, core_count, clock_speeds, vram, bus_width)
                VALUES (?, 4096, 2.5, 16, 256)
                """, gpuId);
        price(gpuId, "799.00", "gpu-real-price", CURRENT_AT);
        benchmark(gpuId, "3DMark Time Spy", "15000", "points", true,
                "gpu-real-benchmark", CURRENT_AT);
    }

    @Test
    void persistsRepeatableHistoryForPhonesAndGpusWithoutChangingEffectiveDataOrSpecs() {
        int eventsBefore = db.queryForObject("SELECT count(*) FROM market_events", Integer.class);
        Map<String, Object> phoneSpecsBefore = db.queryForMap(
                "SELECT chipset, ram_gb, storage_gb, battery_mah FROM phone WHERE product_id = ?", phoneId);
        Map<String, Object> gpuSpecsBefore = db.queryForMap(
                "SELECT core_count, clock_speeds, vram, bus_width FROM gpu WHERE product_id = ?", gpuId);

        var phoneCommand = command(phoneId, 4, 17L);
        var phoneResult = service.generate(phoneCommand);

        assertFalse(phoneResult.alreadyGenerated());
        assertEquals("SMARTPHONE", phoneResult.category());
        assertTrue(phoneResult.source().matches("simulated-history:" + phoneId + ":[0-9a-f]{16}"));
        assertEquals(4, phoneResult.priceRowsInserted());
        assertEquals(8, phoneResult.benchmarkRowsInserted());
        assertChronologicalRows("price_history", phoneId, phoneResult.source(), 4);
        assertChronologicalRows("benchmark_results", phoneId, phoneResult.source(), 8);

        var firstPrices = db.queryForList(
                "SELECT price FROM price_history WHERE product_id = ? AND source = ? ORDER BY observed_at, id",
                BigDecimal.class, phoneId, phoneResult.source());

        var repeated = service.generate(phoneCommand);
        assertTrue(repeated.alreadyGenerated());
        assertEquals(0, repeated.priceRowsInserted());
        assertEquals(0, repeated.benchmarkRowsInserted());
        assertEquals(4, repeated.existingPriceRows());
        assertEquals(8, repeated.existingBenchmarkRows());
        assertEquals(firstPrices, db.queryForList(
                "SELECT price FROM price_history WHERE product_id = ? AND source = ? ORDER BY observed_at, id",
                BigDecimal.class, phoneId, phoneResult.source()));

        var gpuResult = service.generate(command(gpuId, 3, 29L));
        assertEquals("GPU", gpuResult.category());
        assertEquals(3, gpuResult.priceRowsInserted());
        assertEquals(3, gpuResult.benchmarkRowsInserted());

        assertEquals(0, new BigDecimal("999.00").compareTo(
                products.findLatestPrice(phoneId).orElseThrow().getPrice()));
        assertEquals("phone-real-price", latestSource("price_history", phoneId));
        assertEquals(List.of("phone-real-benchmark", "phone-real-benchmark"),
                benchmarks.findLatestPerBenchmark(phoneId).stream()
                        .map(result -> result.getSource())
                        .sorted()
                        .toList());
        assertEquals(0, new BigDecimal("799.00").compareTo(
                products.findLatestPrice(gpuId).orElseThrow().getPrice()));
        assertEquals("gpu-real-benchmark",
                benchmarks.findLatestPerBenchmark(gpuId).getFirst().getSource());

        assertEquals(phoneSpecsBefore, db.queryForMap(
                "SELECT chipset, ram_gb, storage_gb, battery_mah FROM phone WHERE product_id = ?", phoneId));
        assertEquals(gpuSpecsBefore, db.queryForMap(
                "SELECT core_count, clock_speeds, vram, bus_width FROM gpu WHERE product_id = ?", gpuId));
        assertEquals(eventsBefore, db.queryForObject("SELECT count(*) FROM market_events", Integer.class));
    }

    @Test
    void rejectsAHistoryThatCouldBecomeTheLatestEffectiveObservation() {
        var unsafe = new SimulatedHistoryService.Command(
                phoneId,
                Instant.parse("2026-09-02T00:00:00Z"),
                2,
                Duration.ofDays(30),
                5L,
                new BigDecimal("0.15"),
                new BigDecimal("0.03"));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> service.generate(unsafe));
        assertTrue(failure.getMessage().contains("must end before"));
        assertEquals(0, db.queryForObject(
                "SELECT count(*) FROM price_history WHERE product_id = ? AND source LIKE 'simulated-history:%'",
                Integer.class, phoneId));
        assertEquals(0, db.queryForObject(
                "SELECT count(*) FROM benchmark_results WHERE product_id = ? AND source LIKE 'simulated-history:%'",
                Integer.class, phoneId));
    }

    @Test
    void refusesToTreatAPreviouslyDamagedScenarioAsACompleteIdempotentRerun() {
        var command = command(phoneId, 3, 41L);
        var first = service.generate(command);
        db.update("""
                DELETE FROM benchmark_results
                WHERE id = (
                    SELECT id FROM benchmark_results
                    WHERE product_id = ? AND source = ?
                    ORDER BY id LIMIT 1
                )
                """, phoneId, first.source());

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> service.generate(command));
        assertTrue(failure.getMessage().contains("incomplete"));
        assertEquals(3, db.queryForObject(
                "SELECT count(*) FROM price_history WHERE product_id = ? AND source = ?",
                Integer.class, phoneId, first.source()));
    }

    @Test
    void supportsProductsWithOnlyOneKindOfGenuineBaseline() {
        long priceOnly = product("Price Only", "SMARTPHONE");
        db.update("INSERT INTO phone(product_id, chipset) VALUES (?, 'Price Chip')", priceOnly);
        price(priceOnly, "499.00", "price-only-real", CURRENT_AT);

        var priceResult = service.generate(command(priceOnly, 2, 51L));
        assertEquals(2, priceResult.priceRowsInserted());
        assertEquals(0, priceResult.benchmarkRowsInserted());
        assertTrue(service.generate(command(priceOnly, 2, 51L)).alreadyGenerated());

        long benchmarkOnly = product("Benchmark Only", "GPU");
        db.update("INSERT INTO gpu(product_id, core_count) VALUES (?, 1024)", benchmarkOnly);
        benchmark(benchmarkOnly, "3DMark", "9000", "points", true,
                "benchmark-only-real", CURRENT_AT);

        var benchmarkResult = service.generate(command(benchmarkOnly, 2, 52L));
        assertEquals(0, benchmarkResult.priceRowsInserted());
        assertEquals(2, benchmarkResult.benchmarkRowsInserted());
        assertTrue(service.generate(command(benchmarkOnly, 2, 52L)).alreadyGenerated());
    }

    private SimulatedHistoryService.Command command(long productId, int count, long seed) {
        return new SimulatedHistoryService.Command(
                productId,
                Instant.parse("2025-01-01T00:00:00Z"),
                count,
                Duration.ofDays(30),
                seed,
                new BigDecimal("0.15"),
                new BigDecimal("0.03"));
    }

    private long product(String model, String category) {
        return db.queryForObject("""
                INSERT INTO products(brand, model_name, category, status)
                VALUES (?, ?, ?, 'VERIFIED') RETURNING id
                """, Long.class, BRAND, model, category);
    }

    private void price(long productId, String price, String source, Instant observedAt) {
        db.update("""
                INSERT INTO price_history(product_id, price, currency, source, observed_at)
                VALUES (?, ?, 'SGD', ?, ?)
                """, productId, new BigDecimal(price), source, OffsetDateTime.ofInstant(observedAt, java.time.ZoneOffset.UTC));
    }

    private void benchmark(
            long productId,
            String name,
            String score,
            String unit,
            boolean higherIsBetter,
            String source,
            Instant observedAt) {
        db.update("""
                INSERT INTO benchmark_results
                    (product_id, benchmark_name, score, unit, higher_is_better, source, observed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, productId, name, new BigDecimal(score), unit, higherIsBetter, source,
                OffsetDateTime.ofInstant(observedAt, java.time.ZoneOffset.UTC));
    }

    private void assertChronologicalRows(String table, long productId, String source, int expectedCount) {
        List<OffsetDateTime> timestamps = db.queryForList(
                "SELECT observed_at FROM " + table
                        + " WHERE product_id = ? AND source = ? ORDER BY observed_at, id",
                OffsetDateTime.class, productId, source);
        assertEquals(expectedCount, timestamps.size());
        for (int index = 1; index < timestamps.size(); index++) {
            assertFalse(timestamps.get(index).isBefore(timestamps.get(index - 1)));
        }
        assertTrue(timestamps.getLast().toInstant().isBefore(CURRENT_AT));
    }

    private String latestSource(String table, long productId) {
        return db.queryForObject(
                "SELECT source FROM " + table
                        + " WHERE product_id = ? ORDER BY observed_at DESC, id DESC LIMIT 1",
                String.class, productId);
    }
}
