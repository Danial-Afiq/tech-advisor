package com.springboot.backend.ingestion;

import com.springboot.backend.ingestion.core.IngestionSink;
import com.springboot.backend.ingestion.core.IngestionSource;
import com.springboot.backend.ingestion.core.Payload;
import com.springboot.backend.ingestion.core.SourceContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
        db.update("DELETE FROM market_events");
        db.update("DELETE FROM phone_variants");
        db.update("DELETE FROM phone");
        db.update("DELETE FROM products");
    }

    private Payload specPayload(String extId, String brand, String model, String chipset,
            Map<String, BigDecimal> values, Map<String, String> units) {
        return specPayload(extId, brand, model, chipset, values, units, List.of());
    }

    private Payload specPayload(String extId, String brand, String model, String chipset,
            Map<String, BigDecimal> values, Map<String, String> units, List<Integer> storageOptionsGb) {
        return new Payload("mobileapi-smartphone", extId, Instant.now(),
                new Payload.Specifications(extId, brand, model, chipset, values, units, storageOptionsGb));
    }

    @Test void suppportsOnlyMobileApiSpecifications() {
        var spec = new Payload.Specifications("1", "B", "M", "C",
                Map.of("ram", BigDecimal.ONE), Map.of("ram", "GB"), List.of());
        assertTrue(sink.supports(MOBILEAPI, spec));
        assertFalse(sink.supports(OTHER, spec));
        assertFalse(sink.supports(MOBILEAPI, new Payload.Price("1", null, BigDecimal.ONE, "SGD")));
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

    @Test void noStorageTierListFallsBackToOneVariantFromTheBaseFigure() {
        // Ticket 1.8: older/budget devices commonly have no tier breakdown at all (confirmed
        // against real captured devices) - must still get one variant, not zero.
        var payload = specPayload("31333", "BLU", "G5", "Snapdragon 8 Gen 3",
                Map.of("ram", new BigDecimal("8"), "storage", new BigDecimal("256"), "battery", new BigDecimal("5000")),
                Map.of("ram", "GB", "storage", "GB", "battery", "mAh"));
        sink.accept("run-1", payload);

        var product = db.queryForMap("SELECT id FROM products WHERE brand = 'BLU' AND model_name = 'G5'");
        var variants = db.queryForList("SELECT * FROM phone_variants WHERE product_id = ?", product.get("id"));
        assertEquals(1, variants.size());
        assertEquals("Snapdragon 8 Gen 3", variants.getFirst().get("chipset"));
        assertEquals(8, variants.getFirst().get("ram_gb"));
        assertEquals(256, variants.getFirst().get("storage_gb"));
        assertEquals(5000, variants.getFirst().get("battery_mah"));
    }

    @Test void bothStorageTierListAndBaseFigureUnknownStillGetsOneVariantRow() {
        // Real finding (ticket 1.8, live run): a device whose storage text is entirely
        // unparseable yields neither a tier list nor a base figure - without this case, that
        // product's phone row got written but phone_variants stayed completely empty for it,
        // the exact gap this whole change exists to close. Confirmed live against a real
        // Keneksi Glass pull before this was handled, not hypothesized.
        var payload = specPayload("148", "Keneksi", "Keneksi Glass", null,
                Map.of("ram", new BigDecimal("1")), Map.of("ram", "GB"));
        sink.accept("run-1", payload);

        var product = db.queryForMap("SELECT id FROM products WHERE brand = 'Keneksi' AND model_name = 'Keneksi Glass'");
        var variants = db.queryForList("SELECT * FROM phone_variants WHERE product_id = ?", product.get("id"));
        assertEquals(1, variants.size());
        assertNull(variants.getFirst().get("storage_gb"));
        assertEquals(1, variants.getFirst().get("ram_gb"));
    }

    @Test void realStorageTierListCreatesOneVariantPerTier() {
        // Real iPhone 17 Pro shape: "256GB, 512GB, 1TB" (ticket 1.8, GET /devices/43/).
        var payload = specPayload("43", "Apple", "iPhone 17 Pro", "Apple A19 Pro",
                Map.of("ram", new BigDecimal("12"), "battery", new BigDecimal("3998")),
                Map.of("ram", "GB", "battery", "mAh"), List.of(256, 512, 1024));
        sink.accept("run-1", payload);

        var product = db.queryForMap("SELECT id FROM products WHERE brand = 'Apple' AND model_name = 'iPhone 17 Pro'");
        var tiers = db.queryForList(
                "SELECT storage_gb FROM phone_variants WHERE product_id = ? ORDER BY storage_gb",
                Integer.class, product.get("id"));
        assertEquals(List.of(256, 512, 1024), tiers);
        // Same chipset/ram on every tier - MobileAPI gives one of each for the whole model.
        var chipsets = db.queryForList(
                "SELECT DISTINCT chipset, ram_gb FROM phone_variants WHERE product_id = ?", product.get("id"));
        assertEquals(1, chipsets.size());
        assertEquals("Apple A19 Pro", chipsets.getFirst().get("chipset"));
        assertEquals(12, chipsets.getFirst().get("ram_gb"));
    }

    @Test void reingestingTheSameTiersUpsertsRatherThanDuplicatingVariants() {
        var payload = specPayload("43", "Apple", "iPhone 17 Pro", "Apple A19 Pro",
                Map.of("ram", new BigDecimal("12"), "battery", new BigDecimal("3998")),
                Map.of("ram", "GB", "battery", "mAh"), List.of(256, 512));
        sink.accept("run-1", payload);
        sink.accept("run-2", payload);

        var product = db.queryForMap("SELECT id FROM products WHERE brand = 'Apple' AND model_name = 'iPhone 17 Pro'");
        assertEquals(2, db.queryForObject(
                "SELECT count(*) FROM phone_variants WHERE product_id = ?", Integer.class, product.get("id")));
    }

    @Test void reingestingWithChipsetNowMissingReconcilesIntoTheExistingVariantRatherThanDuplicating() {
        // Review feedback (PR #46): an existing (storage=256, ram=12, chipset="Apple A19 Pro")
        // variant must NOT get a second, incomplete row when a later run's hardware text fails
        // to parse a chipset (NULL) - the old exact-match ON CONFLICT key let that slip through.
        var first = specPayload("43", "Apple", "iPhone 17 Pro", "Apple A19 Pro",
                Map.of("ram", new BigDecimal("12")), Map.of("ram", "GB"), List.of(256));
        sink.accept("run-1", first);
        var chipsetUnparseable = specPayload("43", "Apple", "iPhone 17 Pro", null,
                Map.of("ram", new BigDecimal("12")), Map.of("ram", "GB"), List.of(256));
        sink.accept("run-2", chipsetUnparseable);

        var product = db.queryForMap("SELECT id FROM products WHERE brand = 'Apple' AND model_name = 'iPhone 17 Pro'");
        var variants = db.queryForList("SELECT * FROM phone_variants WHERE product_id = ?", product.get("id"));
        assertEquals(1, variants.size());
        assertEquals("Apple A19 Pro", variants.getFirst().get("chipset"), "known chipset must survive, not be overwritten");
    }

    @Test void reingestingWithRamNowMissingReconcilesIntoTheExistingVariantRatherThanDuplicating() {
        var first = specPayload("43", "Apple", "iPhone 17 Pro", "Apple A19 Pro",
                Map.of("ram", new BigDecimal("12")), Map.of("ram", "GB"), List.of(256));
        sink.accept("run-1", first);
        var ramUnparseable = specPayload("43", "Apple", "iPhone 17 Pro", "Apple A19 Pro",
                Map.of(), Map.of(), List.of(256));
        sink.accept("run-2", ramUnparseable);

        var product = db.queryForMap("SELECT id FROM products WHERE brand = 'Apple' AND model_name = 'iPhone 17 Pro'");
        var variants = db.queryForList("SELECT * FROM phone_variants WHERE product_id = ?", product.get("id"));
        assertEquals(1, variants.size());
        assertEquals(12, variants.getFirst().get("ram_gb"), "known RAM must survive, not be overwritten");
    }

    @Test void reingestingWithStorageNowUnknownReconcilesWhenOnlyOneVariantExists() {
        // Same reconciliation logic covers storage going unknown: with only one existing
        // variant, a later run whose storage text is entirely unparseable (NULL) still has
        // exactly one compatible candidate (chipset and RAM both still agree), so it updates
        // that row rather than adding a second "unknown storage" one for the same phone.
        var known = specPayload("148", "Keneksi", "Keneksi Glass", "MediaTek Helio A22",
                Map.of("ram", new BigDecimal("1")), Map.of("ram", "GB"), List.of(16));
        sink.accept("run-1", known);
        var storageUnparseable = specPayload("148", "Keneksi", "Keneksi Glass", "MediaTek Helio A22",
                Map.of("ram", new BigDecimal("1")), Map.of("ram", "GB"));
        sink.accept("run-2", storageUnparseable);

        var product = db.queryForMap("SELECT id FROM products WHERE brand = 'Keneksi' AND model_name = 'Keneksi Glass'");
        var variants = db.queryForList("SELECT * FROM phone_variants WHERE product_id = ?", product.get("id"));
        assertEquals(1, variants.size());
        assertEquals(16, variants.getFirst().get("storage_gb"), "known storage must survive, not be overwritten");
    }

    @Test void previouslyUnknownStorageIsFilledWhenStorageTiersBecomeAvailable() {
        // The first MobileAPI response does not report storage; a later response lists
        // all three tiers. Fill the existing unknown row with the first known tier,
        // then create the remaining two variants instead of repeatedly updating NULL.
        var unknownStorage = specPayload("43", "Apple", "iPhone 17 Pro", "Apple A19 Pro",
                Map.of("ram", new BigDecimal("12"), "battery", new BigDecimal("3998")),
                Map.of("ram", "GB", "battery", "mAh"));
        sink.accept("run-1", unknownStorage);

        long productId = db.queryForObject(
                "SELECT id FROM products WHERE brand = 'Apple' AND model_name = 'iPhone 17 Pro'",
                Long.class);
        long originalVariantId = db.queryForObject(
                "SELECT id FROM phone_variants WHERE product_id = ?", Long.class, productId);
        assertNull(db.queryForObject(
                "SELECT storage_gb FROM phone_variants WHERE id = ?", Integer.class, originalVariantId));

        var knownTiers = specPayload("43", "Apple", "iPhone 17 Pro", "Apple A19 Pro",
                Map.of("ram", new BigDecimal("12"), "battery", new BigDecimal("3998")),
                Map.of("ram", "GB", "battery", "mAh"), List.of(256, 512, 1024));
        sink.accept("run-2", knownTiers);

        assertEquals(List.of(256, 512, 1024), db.queryForList(
                "SELECT storage_gb FROM phone_variants WHERE product_id = ? ORDER BY storage_gb",
                Integer.class, productId));
        assertEquals(originalVariantId, db.queryForObject(
                "SELECT id FROM phone_variants WHERE product_id = ? AND storage_gb = 256",
                Long.class, productId));
        // Repeating complete data must not create any more variants.
        sink.accept("run-3", knownTiers);
        assertEquals(3, db.queryForObject(
                "SELECT COUNT(*) FROM phone_variants WHERE product_id = ?", Integer.class, productId));
    }

    @Test void genuinelyDistinctChipsetsStayAsSeparateVariantsNotMergedByReconciliation() {
        // The reconciliation leniency only applies when a field is UNKNOWN on one side - two
        // real, known chipsets for the same storage/RAM must stay two rows, never merged.
        var snapdragon = specPayload("200", "Samsung", "Galaxy S99", "Snapdragon 8 Gen 5",
                Map.of("ram", new BigDecimal("12")), Map.of("ram", "GB"), List.of(256));
        sink.accept("run-1", snapdragon);
        var exynos = specPayload("200", "Samsung", "Galaxy S99", "Exynos 2600",
                Map.of("ram", new BigDecimal("12")), Map.of("ram", "GB"), List.of(256));
        sink.accept("run-2", exynos);

        var product = db.queryForMap("SELECT id FROM products WHERE brand = 'Samsung' AND model_name = 'Galaxy S99'");
        var chipsets = db.queryForList(
                "SELECT chipset FROM phone_variants WHERE product_id = ? ORDER BY chipset", product.get("id"));
        assertEquals(2, chipsets.size());
        assertEquals("Exynos 2600", chipsets.get(0).get("chipset"));
        assertEquals("Snapdragon 8 Gen 5", chipsets.get(1).get("chipset"));
    }

    @Test void ambiguousReconciliationAcrossTwoIncompleteRowsIsLeftAlone() {
        // Two existing rows, each missing a DIFFERENT identity field, both become compatible
        // candidates for an ingest that supplies both fields - genuinely ambiguous, so this
        // ingest must not guess which one to update (and must not add a third row either).
        var product = db.queryForMap(
                "INSERT INTO products (brand, model_name, category) VALUES ('Acme', 'Ambi 1', 'SMARTPHONE') RETURNING id");
        long productId = ((Number) product.get("id")).longValue();
        db.update("INSERT INTO phone_variants (product_id, chipset, ram_gb, storage_gb) VALUES (?, NULL, 8, 128)", productId);
        db.update("INSERT INTO phone_variants (product_id, chipset, ram_gb, storage_gb) VALUES (?, 'Chip X', NULL, 128)", productId);

        var ambiguous = specPayload("300", "Acme", "Ambi 1", "Chip X",
                Map.of("ram", new BigDecimal("8")), Map.of("ram", "GB"), List.of(128));
        sink.accept("run-1", ambiguous);

        var variants = db.queryForList(
                "SELECT chipset, ram_gb FROM phone_variants WHERE product_id = ? ORDER BY chipset NULLS FIRST", productId);
        assertEquals(2, variants.size(), "must not merge into either candidate or add a third row");
        assertNull(variants.get(0).get("chipset"));
        assertEquals(8, variants.get(0).get("ram_gb"));
        assertEquals("Chip X", variants.get(1).get("chipset"));
        assertNull(variants.get(1).get("ram_gb"));
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

    @Test void aNewProductRecordsOneLaunchEventAndReingestingRecordsNone() {
        var payload = specPayload("1", "BLU", "G5", "Snapdragon",
                Map.of("ram", new BigDecimal("4")), Map.of("ram", "GB"));
        sink.accept("run-1", payload);
        sink.accept("run-2", payload);

        Long productId = db.queryForObject(
                "SELECT id FROM products WHERE brand = 'BLU' AND model_name = 'G5'", Long.class);
        var events = db.queryForList("SELECT event_type, source FROM market_events WHERE product_id = ?", productId);
        assertEquals(1, events.size(), "only the first sighting is a launch");
        assertEquals("PRODUCT_LAUNCH", events.getFirst().get("event_type"));
        assertEquals("mobileapi-smartphone", events.getFirst().get("source"));
    }
}
