package com.springboot.backend.ingestion;

import com.springboot.backend.ingestion.core.IngestionSink;
import com.springboot.backend.ingestion.core.IngestionSource;
import com.springboot.backend.ingestion.core.Payload;
import com.springboot.backend.ingestion.core.SourceContext;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Postgres, real schema - ticket 1.8's first real writer for price_history
 * (Payload.Price existed since ticket 1.1 but nothing emitted or accepted it before this).
 */
@SpringBootTest(properties = "ingestion.reconciliation-enabled=false")
class PriceHistorySinkTests {
    @Autowired PriceHistorySink sink;
    @Autowired JdbcTemplate db;

    private static final IngestionSource SOME_SOURCE = new IngestionSource() {
        public String sourceId() { return "searchapi-google-product-reviews"; }
        public void ingest(SourceContext c, java.util.function.Consumer<Payload> o) {}
    };

    long productId, variantId;

    @BeforeEach void reset() {
        assertTrue(db.queryForObject("SELECT current_database()", String.class).endsWith("_test"),
                "Integration tests require a dedicated database whose name ends in _test");
        db.update("DELETE FROM price_history WHERE product_id IN (SELECT id FROM products WHERE brand='PriceSinkTest')");
        db.update("DELETE FROM phone_variants WHERE product_id IN (SELECT id FROM products WHERE brand='PriceSinkTest')");
        db.update("DELETE FROM products WHERE brand='PriceSinkTest'");
        productId = db.queryForObject(
                "INSERT INTO products(brand,model_name,category,status) VALUES ('PriceSinkTest','Phone','SMARTPHONE','VERIFIED') RETURNING id",
                Long.class);
        variantId = db.queryForObject(
                "INSERT INTO phone_variants(product_id,storage_gb) VALUES (?,256) RETURNING id", Long.class, productId);
    }

    @Test void supportsOnlyPricePayloadsFromRealNonSimulationSources() {
        assertTrue(sink.supports(SOME_SOURCE, new Payload.Price("1", null, BigDecimal.ONE, "USD")));
        assertFalse(sink.supports(SOME_SOURCE, new Payload.ReviewBatch(1, java.util.List.of(
                new Payload.Review("a".repeat(64), "x.com", "t", "text", BigDecimal.ONE, "today", Instant.now())))));
        // SimulationSink already claims every payload type from a simulation source - this sink
        // must stay out of its way, or the orchestrator's "exactly one sink" rule rejects both.
        var simulationSource = new IngestionSource() {
            public String sourceId() { return "simulated-release"; }
            public boolean simulation() { return true; }
            public void ingest(SourceContext c, java.util.function.Consumer<Payload> o) {}
        };
        assertFalse(sink.supports(simulationSource, new Payload.Price("1", null, BigDecimal.ONE, "USD")));
    }

    @Test void persistsAModelLevelPriceWithNoVariant() {
        var payload = new Payload("searchapi-google-product-reviews", "p1", Instant.parse("2026-10-09T00:00:00Z"),
                new Payload.Price(String.valueOf(productId), null, new BigDecimal("999.00"), "USD"));
        assertEquals(IngestionSink.Result.ACCEPTED, sink.accept("run-1", payload));

        var row = db.queryForMap("SELECT * FROM price_history WHERE product_id=?", productId);
        assertEquals(0, new BigDecimal("999.00").compareTo((BigDecimal) row.get("price")));
        assertEquals("USD", row.get("currency"));
        assertEquals("searchapi-google-product-reviews", row.get("source"));
        assertNull(row.get("phone_variant_id"));
    }

    @Test void persistsAVariantScopedPriceWhenOneIsGiven() {
        var payload = new Payload("searchapi-google-product-reviews", "p2", Instant.parse("2026-10-09T00:00:00Z"),
                new Payload.Price(String.valueOf(productId), String.valueOf(variantId), new BigDecimal("1299.00"), "USD"));
        assertEquals(IngestionSink.Result.ACCEPTED, sink.accept("run-1", payload));

        var row = db.queryForMap("SELECT * FROM price_history WHERE phone_variant_id=?", variantId);
        assertEquals(0, new BigDecimal("1299.00").compareTo((BigDecimal) row.get("price")));
        assertEquals(productId, ((Number) row.get("product_id")).longValue());
    }

    @Test void neverDedupesObservationsAccumulateAsATimeSeries() {
        var payload = new Payload("searchapi-google-product-reviews", "p3", Instant.parse("2026-10-09T00:00:00Z"),
                new Payload.Price(String.valueOf(productId), null, new BigDecimal("999.00"), "USD"));
        sink.accept("run-1", payload);
        assertEquals(IngestionSink.Result.ACCEPTED, sink.accept("run-2", payload));
        assertEquals(2, db.queryForObject("SELECT count(*) FROM price_history WHERE product_id=?", Integer.class, productId));
    }
}
