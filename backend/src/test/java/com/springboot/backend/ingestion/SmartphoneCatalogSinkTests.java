package com.springboot.backend.ingestion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Postgres, real schema (V6__create_sprint_1_schema.sql) — this sink is
 * pure SQL with no JPA entities to fall back on, so its actual persistence
 * behaviour can only be verified against a real database, not a mock.
 */
@SpringBootTest(properties = "ingestion.reconciliation-enabled=false")
class SmartphoneCatalogSinkTests {
    @Autowired SmartphoneCatalogSink sink;
    @Autowired JdbcTemplate db;

    private static final IngestionSource MOBILEAPI = new IngestionSource() {
        public String sourceId() { return "mobileapi-smartphone"; }
        public void ingest(SourceContext c, java.util.function.Consumer<Payload> o) {}
    };
    private static final IngestionSource OTHER = new IngestionSource() {
        public String sourceId() { return "some-other-source"; }
        public void ingest(SourceContext c, java.util.function.Consumer<Payload> o) {}
    };

    @BeforeEach void reset() {
        assertTrue(db.queryForObject("SELECT current_database()", String.class).endsWith("_test"),
                "Integration tests require a dedicated database whose name ends in _test");
        db.update("DELETE FROM phone");
        db.update("DELETE FROM products");
    }

    private Payload specPayload(String extId, String brand, String model, String chipset,
            Map<String, BigDecimal> values, Map<String, String> units) {
        return new Payload("mobileapi-smartphone", extId, Instant.now(),
                new Payload.Specifications(extId, brand, model, chipset, values, units));
    }

    @Test void suppportsOnlyMobileApiSpecifications() {
        var spec = new Payload.Specifications("1", "B", "M", "C",
                Map.of("ram", BigDecimal.ONE), Map.of("ram", "GB"));
        assertTrue(sink.supports(MOBILEAPI, spec));
        assertFalse(sink.supports(OTHER, spec));
        assertFalse(sink.supports(MOBILEAPI, new Payload.Price("1", BigDecimal.ONE, "SGD")));
    }

    @Test void persistsProductAndPhoneRowsWithAllFieldsPresent() {
        var payload = specPayload("31333", "BLU", "G5", "Snapdragon 8 Gen 3",
                Map.of("ram", new BigDecimal("8"), "storage", new BigDecimal("256"), "battery", new BigDecimal("5000")),
                Map.of("ram", "GB", "storage", "GB", "battery", "mAh"));
        assertEquals(IngestionSink.Result.ACCEPTED, sink.accept("run-1", payload));

        var product = db.queryForMap("SELECT * FROM products WHERE brand = 'BLU' AND model_name = 'G5'");
        assertEquals("SMARTPHONE", product.get("category"));
        assertEquals("VERIFIED", product.get("status"));

        var phone = db.queryForMap("SELECT * FROM phone WHERE product_id = ?", product.get("id"));
        assertEquals("Snapdragon 8 Gen 3", phone.get("chipset"));
        assertEquals(8, phone.get("ram_gb"));
        assertEquals(256, phone.get("storage_gb"));
        assertEquals(5000, phone.get("battery_mah"));
    }

    @Test void missingOptionalFieldsPersistAsNullNotAsBlockingTheRecord() {
        // Only ram present - matches a real device where camera/storage/battery came back empty.
        var payload = specPayload("1", "Acme", "Budget Phone", null,
                Map.of("ram", new BigDecimal("2")), Map.of("ram", "GB"));
        assertEquals(IngestionSink.Result.ACCEPTED, sink.accept("run-1", payload));

        var phone = db.queryForMap("SELECT * FROM phone p JOIN products pr ON pr.id = p.product_id "
                + "WHERE pr.brand = 'Acme' AND pr.model_name = 'Budget Phone'");
        assertEquals(2, phone.get("ram_gb"));
        assertNull(phone.get("chipset"));
        assertNull(phone.get("storage_gb"));
        assertNull(phone.get("battery_mah"));
    }

    @Test void reingestingTheSameModelUpsertsRatherThanDuplicating() {
        var first = specPayload("1", "BLU", "G5", "Old Chipset",
                Map.of("ram", new BigDecimal("4")), Map.of("ram", "GB"));
        sink.accept("run-1", first);
        var second = specPayload("1", "BLU", "G5", "New Chipset",
                Map.of("ram", new BigDecimal("6")), Map.of("ram", "GB"));
        sink.accept("run-2", second);

        assertEquals(1, db.queryForObject(
                "SELECT count(*) FROM products WHERE brand = 'BLU' AND model_name = 'G5'", Integer.class));
        var phone = db.queryForMap("SELECT * FROM phone p JOIN products pr ON pr.id = p.product_id "
                + "WHERE pr.brand = 'BLU' AND pr.model_name = 'G5'");
        assertEquals("New Chipset", phone.get("chipset"));
        assertEquals(6, phone.get("ram_gb"));
    }

    @Test void reingestWithAFieldNowMissingPreservesThePreviousValue() {
        var withChipset = specPayload("1", "BLU", "G5", "Snapdragon",
                Map.of("ram", new BigDecimal("4")), Map.of("ram", "GB"));
        sink.accept("run-1", withChipset);
        // A later run where the source's hardware text didn't parse a chipset this time.
        var withoutChipset = specPayload("1", "BLU", "G5", null,
                Map.of("ram", new BigDecimal("4")), Map.of("ram", "GB"));
        sink.accept("run-2", withoutChipset);

        var phone = db.queryForMap("SELECT * FROM phone p JOIN products pr ON pr.id = p.product_id "
                + "WHERE pr.brand = 'BLU' AND pr.model_name = 'G5'");
        assertEquals("Snapdragon", phone.get("chipset"));
    }
}
