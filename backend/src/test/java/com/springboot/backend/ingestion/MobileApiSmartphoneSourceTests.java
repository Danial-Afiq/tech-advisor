package com.springboot.backend.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springboot.backend.ingestion.core.Payload;
import com.springboot.backend.ingestion.core.SourceContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SourceContext.get(URI) always makes a real HTTP call with no seam to fake
 * it, so fetchList()/ingest()'s own network line need a live API key and a
 * real (or demo-script) run to verify end to end — see docs/ingestion.md's
 * demo script pattern. Everything else - the actual device-JsonNode -> Payload
 * transform - is pure and tested directly here via toPayload(), same split as
 * HardwareZoneReviewParser/Source. MobileApiFieldExtractorTests covers the
 * lower-level field parsing toPayload() calls into.
 */
class MobileApiSmartphoneSourceTests {
    private final ObjectMapper json = new ObjectMapper();

    @Test void sourceIdIsStableAndValidForTheRegistry() {
        var source = new MobileApiSmartphoneSource("", "");
        assertEquals("mobileapi-smartphone", source.sourceId());
        assertTrue(source.sourceId().matches("[a-z0-9-]{1,80}"));
    }

    @Test void noApiKeyConfiguredEmitsNothingRatherThanFailing() throws Exception {
        var source = new MobileApiSmartphoneSource("", "");
        var context = new SourceContext(Clock.systemUTC(), () -> {});
        List<Payload> emitted = new ArrayList<>();
        try {
            source.ingest(context, emitted::add);
        } finally {
            context.close();
        }
        assertTrue(emitted.isEmpty());
    }

    // Fixture below matches the real list-response shape captured against the live API
    // (mobileapi-response.json) - id/name/manufacturer_name/hardware/storage/battery_capacity/camera.
    private JsonNode device(String json_) throws Exception { return json.readTree(json_); }

    @Test void wellFormedDeviceProducesASpecificationsPayload() throws Exception {
        var device = device("""
                {"id": 31333, "name": "G5", "manufacturer_name": "BLU",
                 "hardware": "Snapdragon 8 Gen 3, 8GB RAM", "storage": "256GB",
                 "battery_capacity": "5000 mAh", "camera": "48 MP + 12 MP + 12 MP"}""");
        Instant now = Instant.parse("2026-09-27T00:00:00Z");

        var payload = MobileApiSmartphoneSource.toPayload("mobileapi-smartphone", now, device).get();
        assertEquals("mobileapi-smartphone", payload.sourceId());
        assertEquals("31333", payload.externalId());
        assertEquals(now, payload.observedAt());
        var spec = (Payload.Specifications) payload.body();
        assertEquals("BLU", spec.brand());
        assertEquals("G5", spec.modelName());
        assertEquals("Snapdragon 8 Gen 3", spec.chipset());
        assertEquals(new java.math.BigDecimal("8"), spec.values().get("ram"));
        assertEquals(new java.math.BigDecimal("256"), spec.values().get("storage"));
        assertEquals(new java.math.BigDecimal("5000"), spec.values().get("battery"));
        assertEquals(new java.math.BigDecimal("48"), spec.values().get("camera"));
        assertEquals(List.of(256), spec.storageOptionsGb());
    }

    @Test void realMultiTierStorageListProducesEveryTier() throws Exception {
        // Real iPhone 17 Pro shape (ticket 1.8, GET /devices/43/) - "256GB, 512GB, 1TB".
        var device = device("""
                {"id": "43", "name": "iPhone 17 Pro", "manufacturer_name": "Apple",
                 "hardware": "12GB RAM, Apple A19 Pro", "storage": "256GB, 512GB, 1TB",
                 "battery_capacity": "3998 mAh"}""");
        var spec = (Payload.Specifications) MobileApiSmartphoneSource.toPayload(
                "mobileapi-smartphone", Instant.now(), device).get().body();
        assertEquals(List.of(256, 512, 1024), spec.storageOptionsGb());
        // values().get("storage") stays the single base-tier figure phone.storage_gb has
        // always held - storageOptionsGb is additive, not a replacement for it.
        assertEquals(new java.math.BigDecimal("256"), spec.values().get("storage"));
    }

    @Test void noStorageTextProducesAnEmptyTierListNotAnError() throws Exception {
        // Real BLU/Alcatel budget-device shape (mobileapi-response.json) - storage is blank.
        var device = device("""
                {"id": "2", "name": "1B (2022)", "manufacturer_name": "Alcatel",
                 "hardware": "1GB RAM", "storage": ""}""");
        var spec = (Payload.Specifications) MobileApiSmartphoneSource.toPayload(
                "mobileapi-smartphone", Instant.now(), device).get().body();
        assertEquals(List.of(), spec.storageOptionsGb());
    }

    @Test void missingDeviceIdIsSkippedEntirely() throws Exception {
        var device = device("""
                {"name": "G5", "manufacturer_name": "BLU", "hardware": "8GB RAM"}""");
        assertTrue(MobileApiSmartphoneSource.toPayload("mobileapi-smartphone", Instant.now(), device).isEmpty());
    }

    @Test void missingModelNameStillEmitsSoValidateCanRejectAndCountIt() throws Exception {
        // AC: a record missing only the required model name must be rejected downstream and
        // counted as an error - not silently dropped here. Has an id and a parseable value so it
        // reaches Payload.validate(), which PayloadTests already confirms rejects blank modelName.
        var device = device("""
                {"id": "7", "manufacturer_name": "BLU", "hardware": "8GB RAM"}""");
        var payload = MobileApiSmartphoneSource.toPayload("mobileapi-smartphone", Instant.now(), device).get();
        var spec = (Payload.Specifications) payload.body();
        assertNull(spec.modelName());
        assertThrows(IllegalArgumentException.class, () -> payload.validate("mobileapi-smartphone"));
    }

    @Test void deviceWithNoParseableValuesAtAllIsSkipped() throws Exception {
        // Has an id, but every text field is blank/unparseable - nothing to persist.
        var device = device("""
                {"id": "9", "name": "Mystery Phone", "manufacturer_name": "Acme",
                 "hardware": "", "storage": "", "battery_capacity": "", "camera": ""}""");
        assertTrue(MobileApiSmartphoneSource.toPayload("mobileapi-smartphone", Instant.now(), device).isEmpty());
    }

    @Test void devicesInReturnsEmptyWhenDevicesFieldMissingOrNotAnArray() throws Exception {
        assertTrue(MobileApiSmartphoneSource.devicesIn(device("{}")).isEmpty());
        assertTrue(MobileApiSmartphoneSource.devicesIn(device("{\"devices\": \"not-a-list\"}")).isEmpty());
    }

    @Test void devicesInCapsAtTheDeviceLimit() throws Exception {
        var eleven = new StringBuilder("{\"devices\": [");
        for (int i = 0; i < 11; i++) eleven.append(i > 0 ? "," : "").append("{\"id\": \"").append(i).append("\"}");
        eleven.append("]}");
        var result = MobileApiSmartphoneSource.devicesIn(device(eleven.toString()));
        assertEquals(10, result.size());
        assertEquals("0", result.get(0).get("id").asText());
        assertEquals("9", result.get(9).get("id").asText());
    }

    @Test void devicesInReturnsAllWhenFewerThanTheLimit() throws Exception {
        var result = MobileApiSmartphoneSource.devicesIn(device("{\"devices\": [{\"id\": \"1\"}, {\"id\": \"2\"}]}"));
        assertEquals(2, result.size());
    }

    @Test void textHelperIsNullSafe() throws Exception {
        assertNull(MobileApiSmartphoneSource.text(null, "name"));
        assertNull(MobileApiSmartphoneSource.text(device("{}"), "name"));
        assertNull(MobileApiSmartphoneSource.text(device("{\"name\": null}"), "name"));
        assertEquals("G5", MobileApiSmartphoneSource.text(device("{\"name\": \"G5\"}"), "name"));
    }
}
