package com.springboot.backend.ingestion.gpu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.springboot.backend.ingestion.core.IngestionSink;
import com.springboot.backend.ingestion.core.IngestionSource;
import com.springboot.backend.ingestion.core.Payload;
import com.springboot.backend.ingestion.core.SourceContext;
import com.springboot.backend.marketevent.MarketEventService;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Every card's model name contains MARK so the test owns its rows in the shared _test database
 * and never touches catalogue rows written by other tests.
 */
@SpringBootTest(properties = "ingestion.reconciliation-enabled=false")
class GpuCatalogSinkIntegrationTest {
    private static final String MARK = "GpuSinkTest";
    private static final String SOURCE_NAME = "seed:gpus.test";
    private static final IngestionSource SOURCE = source(false);

    @Autowired GpuCatalogSink sink;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired GpuSpecValidator validator;

    @BeforeEach
    void reset() {
        assertTrue(db.queryForObject("SELECT current_database()", String.class).endsWith("_test"),
                "Integration tests require a dedicated database whose name ends in _test");
        db.update("DELETE FROM market_events WHERE title LIKE ?", "%" + MARK + "%");
        db.update("DELETE FROM products WHERE model_name LIKE ?", "%" + MARK + "%");
        db.update("DELETE FROM system_log WHERE component = ?", GpuCatalogSink.LOG_COMPONENT);
    }

    @Test
    void acceptsOnlyGpuBatchesFromRealSources() {
        assertTrue(sink.supports(SOURCE, batch(card("4070")).body()));
        assertFalse(sink.supports(source(true), batch(card("4070")).body()));
        assertFalse(sink.supports(SOURCE, new Payload.Price("1", null, BigDecimal.ONE, "USD")));
    }

    @Test
    void storesSpecsLaunchMsrpAndOneLaunchEvent() {
        assertEquals(IngestionSink.Result.ACCEPTED, sink.accept("run-1", batch(card("4070"))));

        Map<String, Object> row = db.queryForMap("""
                SELECT p.brand, p.model_name, p.category, p.release_date, g.base_clock_mhz, g.boost_clock_mhz,
                       g.vram, g.memory_type, g.bus_width, g.tdp_watts, g.architecture, g.source, g.source_url,
                       g.fetched_at
                FROM products p JOIN gpu g ON g.product_id = p.id WHERE p.model_name = ?
                """, "GeForce " + MARK + " 4070");
        assertEquals("NVIDIA", row.get("brand"));
        assertEquals("GPU", row.get("category"));
        assertEquals(LocalDate.of(2024, 1, 17), ((java.sql.Date) row.get("release_date")).toLocalDate());
        assertEquals(1980, row.get("base_clock_mhz"));
        assertEquals(2470, row.get("boost_clock_mhz"));
        assertEquals(0, new BigDecimal("12").compareTo((BigDecimal) row.get("vram")));
        assertEquals("GDDR6X", row.get("memory_type"));
        assertEquals(192, row.get("bus_width"));
        assertEquals(220, row.get("tdp_watts"));
        assertEquals("Ada Lovelace", row.get("architecture"));
        assertEquals(SOURCE_NAME, row.get("source"));
        assertEquals("https://www.techspot.com/specs/gpu/4070.html", row.get("source_url"));
        assertNotNull(row.get("fetched_at"));

        Map<String, Object> price = db.queryForMap("""
                SELECT ph.price, ph.currency, ph.source, ph.observed_at::date AS observed
                FROM price_history ph JOIN products p ON p.id = ph.product_id WHERE p.model_name = ?
                """, "GeForce " + MARK + " 4070");
        assertEquals(0, new BigDecimal("599").compareTo((BigDecimal) price.get("price")));
        assertEquals("USD", price.get("currency"));
        assertEquals("launch_msrp:" + SOURCE_NAME, price.get("source"));
        assertEquals(LocalDate.of(2024, 1, 17), ((java.sql.Date) price.get("observed")).toLocalDate());

        assertEquals(1, launchEvents());
        assertEquals(Map.of("inserted", 1, "updated", 0, "unchanged", 0, "dropped", 0), lastSummary());
    }

    @Test
    void runningTheSameBatchTwiceInsertsNothing() {
        var batch = batch(card("4070"), card("4060"));
        assertEquals(IngestionSink.Result.ACCEPTED, sink.accept("run-1", batch));
        assertEquals(IngestionSink.Result.DUPLICATE, sink.accept("run-2", batch));

        assertEquals(2, count("SELECT count(*) FROM products WHERE model_name LIKE ?"));
        assertEquals(2, count("SELECT count(*) FROM gpu g JOIN products p ON p.id = g.product_id WHERE p.model_name LIKE ?"));
        assertEquals(2, count("SELECT count(*) FROM price_history ph JOIN products p ON p.id = ph.product_id WHERE p.model_name LIKE ?"));
        assertEquals(2, launchEvents());
        assertEquals(Map.of("inserted", 0, "updated", 0, "unchanged", 2, "dropped", 0), lastSummary());
    }

    @Test
    void identityIgnoresCaseSpacingAndADuplicatedVendorPrefix() {
        sink.accept("run-1", batch(card("4070")));
        var respelled = new Payload.GpuSpec("nvidia", "NVIDIA  geforce " + MARK.toLowerCase() + "   4070",
                LocalDate.of(2024, 1, 17), 1980, 2470, new BigDecimal("12"), "GDDR6X", 192, 220,
                new BigDecimal("599"), "Ada Lovelace", null);
        assertEquals(IngestionSink.Result.DUPLICATE, sink.accept("run-2", batch(respelled)));
        assertEquals(1, count("SELECT count(*) FROM products WHERE lower(model_name) LIKE lower(?)"));
    }

    @Test
    void oneInvalidCardInFiveIsDroppedAndLoggedWhileTheRestAreStored() {
        var misread = new Payload.GpuSpec("NVIDIA", "GeForce " + MARK + " Misread", LocalDate.of(2024, 1, 17),
                2, 2, new BigDecimal("12"), null, null, 220, null, null, null);
        sink.accept("run-1", batch(card("4070"), card("4060"), misread, card("3070"), card("3060")));

        assertEquals(4, count("SELECT count(*) FROM products WHERE model_name LIKE ?"));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM products WHERE model_name = ?", Integer.class,
                "GeForce " + MARK + " Misread"));
        assertEquals(Map.of("inserted", 4, "updated", 0, "unchanged", 0, "dropped", 1), lastSummary());
        String drops = db.queryForObject("""
                SELECT metadata->'dropped' FROM system_log WHERE component = ? ORDER BY id DESC LIMIT 1
                """, String.class, GpuCatalogSink.LOG_COMPONENT);
        assertTrue(drops.contains("Misread") && drops.contains("BASE_CLOCK_OUT_OF_RANGE"), drops);
        assertEquals("PARTIAL_FAILURE", db.queryForObject(
                "SELECT status FROM system_log WHERE component = ? ORDER BY id DESC LIMIT 1",
                String.class, GpuCatalogSink.LOG_COMPONENT));
    }

    @Test
    void anExceptionPartWayThroughLeavesNothingFromTheBatch() {
        // A hand-built sink, not @MockitoSpyBean: a bean override forces a separate Spring
        // context with its own connection pool, which exhausts the shared test database.
        var marketEvents = mock(MarketEventService.class);
        doThrow(new IllegalStateException("boom")).when(marketEvents)
                .record(argThat(event -> event.title().contains(MARK + " 3070")));
        var failing = new GpuCatalogSink(db, transactionManager, validator, marketEvents);

        assertThrows(IllegalStateException.class,
                () -> failing.accept("run-1", batch(card("4070"), card("4060"), card("3070"), card("3060"))));

        assertEquals(0, count("SELECT count(*) FROM products WHERE model_name LIKE ?"));
        assertEquals(0, launchEvents());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM system_log WHERE component = ?",
                Integer.class, GpuCatalogSink.LOG_COMPONENT));
    }

    @Test
    void aLaterRunUpdatesPublishedValuesButNeverBlanksKnownOnes() {
        sink.accept("run-1", batch(card("4070")));
        var partial = new Payload.GpuSpec("NVIDIA", "GeForce " + MARK + " 4070", LocalDate.of(2024, 1, 17),
                1980, null, new BigDecimal("12"), null, null, 200, new BigDecimal("549"), null, null);

        assertEquals(IngestionSink.Result.ACCEPTED, sink.accept("run-2", batch(partial)));

        Map<String, Object> row = db.queryForMap("""
                SELECT g.boost_clock_mhz, g.memory_type, g.tdp_watts FROM gpu g
                JOIN products p ON p.id = g.product_id WHERE p.model_name = ?
                """, "GeForce " + MARK + " 4070");
        assertEquals(2470, row.get("boost_clock_mhz"));
        assertEquals("GDDR6X", row.get("memory_type"));
        assertEquals(200, row.get("tdp_watts"));
        // Launch MSRP is history: the first observation stands.
        assertEquals(0, new BigDecimal("599").compareTo(db.queryForObject("""
                SELECT ph.price FROM price_history ph JOIN products p ON p.id = ph.product_id WHERE p.model_name = ?
                """, BigDecimal.class, "GeForce " + MARK + " 4070")));
        assertEquals(1, launchEvents());
        assertEquals(Map.of("inserted", 0, "updated", 1, "unchanged", 0, "dropped", 0), lastSummary());
    }

    @Test
    void aSameNamedProductInAnotherCategoryIsNeverTurnedIntoAGpu() {
        db.update("INSERT INTO products (brand, model_name, category) VALUES ('NVIDIA', ?, 'SMARTPHONE')",
                "GeForce " + MARK + " 4070");

        sink.accept("run-1", batch(card("4070")));

        assertEquals("SMARTPHONE", db.queryForObject("SELECT category FROM products WHERE model_name = ?",
                String.class, "GeForce " + MARK + " 4070"));
        assertEquals(0, count("SELECT count(*) FROM gpu g JOIN products p ON p.id = g.product_id WHERE p.model_name LIKE ?"));
        assertTrue(db.queryForObject("""
                SELECT metadata->>'dropped' FROM system_log WHERE component = ? ORDER BY id DESC LIMIT 1
                """, String.class, GpuCatalogSink.LOG_COMPONENT).contains(GpuCatalogSink.CATEGORY_CONFLICT));
    }

    private static Payload.GpuSpec card(String number) {
        return new Payload.GpuSpec("NVIDIA", "GeForce " + MARK + " " + number, LocalDate.of(2024, 1, 17),
                1980, 2470, new BigDecimal("12"), "GDDR6X", 192, 220, new BigDecimal("599"), "Ada Lovelace",
                URI.create("https://www.techspot.com/specs/gpu/" + number + ".html"));
    }

    private static Payload batch(Payload.GpuSpec... cards) {
        return new Payload("gpu-test", "batch", Instant.now(), new Payload.GpuCatalogBatch(SOURCE_NAME, List.of(cards)));
    }

    private int count(String sql) {
        return db.queryForObject(sql, Integer.class, "%" + MARK + "%");
    }

    private int launchEvents() {
        return db.queryForObject("SELECT count(*) FROM market_events WHERE event_type = 'PRODUCT_LAUNCH' AND title LIKE ?",
                Integer.class, "%" + MARK + "%");
    }

    private Map<String, Integer> lastSummary() {
        return db.queryForObject("""
                SELECT (metadata->>'inserted')::int, (metadata->>'updated')::int, (metadata->>'unchanged')::int,
                       jsonb_array_length(metadata->'dropped')
                FROM system_log WHERE component = ? ORDER BY id DESC LIMIT 1
                """, (rs, n) -> Map.of("inserted", rs.getInt(1), "updated", rs.getInt(2),
                "unchanged", rs.getInt(3), "dropped", rs.getInt(4)), GpuCatalogSink.LOG_COMPONENT);
    }

    private static IngestionSource source(boolean simulation) {
        return new IngestionSource() {
            @Override public String sourceId() { return "gpu-test"; }
            @Override public boolean simulation() { return simulation; }
            @Override public void ingest(SourceContext context, Consumer<Payload> output) {}
        };
    }
}
