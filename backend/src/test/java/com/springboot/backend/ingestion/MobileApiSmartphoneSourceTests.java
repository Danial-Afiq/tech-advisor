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
        var source = new MobileApiSmartphoneSource("", "", null);
        assertEquals("mobileapi-smartphone", source.sourceId());
        assertTrue(source.sourceId().matches("[a-z0-9-]{1,80}"));
    }

    @Test void noApiKeyConfiguredEmitsNothingRatherThanFailing() throws Exception {
        var source = new MobileApiSmartphoneSource("", "", null);
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

    @Test void listingThumbnailBecomesTheImageUrl() throws Exception {
        var device = device("""
                {"id": 31333, "name": "G5", "manufacturer_name": "BLU", "storage": "256GB",
                 "image_url": "https://api.mobileapi.dev/devices/31333/thumb.png"}""");

        var spec = (Payload.Specifications) MobileApiSmartphoneSource.toPayload(
                "mobileapi-smartphone", Instant.now(), device).get().body();
        assertEquals("https://api.mobileapi.dev/devices/31333/thumb.png", spec.imageUrl().toString());
    }

    @Test void missingOrUnusableImageUrlIsDroppedButTheSpecsAreKept() throws Exception {
        for (String image : new String[] {"null", "\"\"", "\"http://insecure.example/a.png\"",
                "\"javascript:alert(1)\"", "\"not a url\"", "\"/relative/thumb.png\""}) {
            var device = device("""
                    {"id": 1, "name": "G5", "manufacturer_name": "BLU", "storage": "256GB", "image_url": %s}"""
                    .formatted(image));
            var payload = MobileApiSmartphoneSource.toPayload("mobileapi-smartphone", Instant.now(), device).get();
            assertNull(((Payload.Specifications) payload.body()).imageUrl(), image);
            assertDoesNotThrow(() -> payload.validate("mobileapi-smartphone"));
        }
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

    // Bugfix (ingestion_cursors): the default/automatic mode used to call the exact same
    // URL every run - no page parameter existed at all - so a scheduled run never
    // discovered anything beyond whatever the first page happened to be. advance() is the
    // pure rule that makes successive runs actually sweep forward instead.

    @Test void advancePagesForwardWithinTheSameYearWhileMorePagesRemain() {
        var next = MobileApiSmartphoneSource.advance(new MobileApiSmartphoneSource.Cursor(2026, 3), 2026, true);
        assertEquals(new MobileApiSmartphoneSource.Cursor(2026, 4), next);
    }

    @Test void advanceRestartsAtPageOneOfTheSameYearOnceTheSweepIsComplete() {
        // The only way a device added to an earlier page of a year already fully swept is
        // ever noticed - without this, a finished sweep would just stop discovering anything.
        var next = MobileApiSmartphoneSource.advance(new MobileApiSmartphoneSource.Cursor(2026, 24), 2026, false);
        assertEquals(new MobileApiSmartphoneSource.Cursor(2026, 1), next);
    }

    @Test void advanceResetsToPageOneTheMomentTheCalendarYearRollsOver() {
        // A cursor left over from last year must not keep paging through a year that's no
        // longer "now" - the new year's releases must start appearing without a manual step.
        var next = MobileApiSmartphoneSource.advance(new MobileApiSmartphoneSource.Cursor(2026, 15), 2027, true);
        assertEquals(new MobileApiSmartphoneSource.Cursor(2027, 1), next);
    }

    // listUri() is the pure URI-construction logic fetchList() wraps around the one real
    // HTTP call (no test seam - see class Javadoc), split out specifically so the request
    // shape itself stays directly testable without network, same split as toPayload/devicesIn.

    @Test void listUriWithNoPageAndBlankYearIsTheOriginalUnfilteredLatestRequest() {
        var uri = MobileApiSmartphoneSource.listUri("key123", new MobileApiSmartphoneSource.Request("", null));
        assertEquals("https://api.mobileapi.dev/devices/?limit=10&key=key123", uri.toString());
    }

    @Test void listUriWithNoPageAndAYearIsTheOriginalManualOverrideRequest() {
        // Unchanged shape for sources.mobileapi.year's explicit manual override - no page
        // parameter at all, same as it always made before this bugfix existed.
        var uri = MobileApiSmartphoneSource.listUri("key123", new MobileApiSmartphoneSource.Request("2024", null));
        assertEquals("https://api.mobileapi.dev/devices/by-year/?year=2024&key=key123", uri.toString());
    }

    @Test void listUriWithAPageSweepsTheByYearListingExplicitly() {
        // The actual fix: a page parameter now exists at all, which the original request
        // shape above never had - this is what lets successive runs sweep forward.
        var uri = MobileApiSmartphoneSource.listUri("key123", new MobileApiSmartphoneSource.Request("2026", 7));
        assertEquals("https://api.mobileapi.dev/devices/by-year/?year=2026&limit=10&page=7&key=key123", uri.toString());
    }

    @Test void listUriUrlEncodesTheApiKeyAndYear() {
        var uri = MobileApiSmartphoneSource.listUri("a b&c", new MobileApiSmartphoneSource.Request("2026", 1));
        assertTrue(uri.toString().contains("key=a+b%26c"));
    }

    // requestFor()/emit()/nextCursor() are ingest()'s own logic, split out the same way -
    // ingest() itself has no test seam (real HTTP), but nothing downstream of a fetched page
    // needs one, so there's no reason any of it stays untestable.

    @Test void requestForUsesTheCursorWhenOneIsGiven() {
        var request = MobileApiSmartphoneSource.requestFor("", new MobileApiSmartphoneSource.Cursor(2026, 5));
        assertEquals(new MobileApiSmartphoneSource.Request("2026", 5), request);
    }

    @Test void requestForFallsBackToTheConfiguredYearWithNoPageWhenThereIsNoCursor() {
        // The manual-override mode (sources.mobileapi.year set) never computes a cursor at
        // all - null here means that mode, not a missing/uninitialized value.
        var request = MobileApiSmartphoneSource.requestFor("2019", null);
        assertEquals(new MobileApiSmartphoneSource.Request("2019", null), request);
    }

    @Test void emitConvertsEveryDeviceOnThePageToAPayload() throws Exception {
        var page = device("""
                {"devices": [
                    {"id": "1", "name": "G5", "manufacturer_name": "BLU", "hardware": "8GB RAM"},
                    {"id": "2", "name": "G6", "manufacturer_name": "BLU", "hardware": "4GB RAM"}
                ]}""");
        List<Payload> emitted = new ArrayList<>();
        MobileApiSmartphoneSource.emit(page, "mobileapi-smartphone", Instant.parse("2026-01-01T00:00:00Z"), emitted::add);
        assertEquals(2, emitted.size());
        assertEquals("1", emitted.get(0).externalId());
        assertEquals("2", emitted.get(1).externalId());
    }

    @Test void emitSkipsDevicesThatProduceNoParseablePayload() throws Exception {
        // deviceWithNoParseableValuesAtAllIsSkipped already covers toPayload() returning
        // empty for this shape directly - this confirms emit() correctly drops it too rather
        // than emitting something empty.
        var page = device("""
                {"devices": [{"id": "9", "name": "Mystery Phone", "manufacturer_name": "Acme", "hardware": ""}]}""");
        List<Payload> emitted = new ArrayList<>();
        MobileApiSmartphoneSource.emit(page, "mobileapi-smartphone", Instant.now(), emitted::add);
        assertTrue(emitted.isEmpty());
    }

    @Test void nextCursorAdvancesUsingThePageHasNextField() throws Exception {
        var page = device("{\"has_next\": true}");
        var next = MobileApiSmartphoneSource.nextCursor(new MobileApiSmartphoneSource.Cursor(2026, 3), 2026, page);
        assertEquals(new MobileApiSmartphoneSource.Cursor(2026, 4), next);
    }

    @Test void nextCursorTreatsAMissingHasNextFieldAsFalse() throws Exception {
        var page = device("{}");
        var next = MobileApiSmartphoneSource.nextCursor(new MobileApiSmartphoneSource.Cursor(2026, 3), 2026, page);
        assertEquals(new MobileApiSmartphoneSource.Cursor(2026, 1), next);
    }

    @Test void currentYearReadsTheYearFromTheContextsClockNotTheSystemClock() {
        // context.now() is a plain Clock read (no network, no DB) - a SourceContext built
        // with a fixed Clock makes this directly testable without a live run.
        var fixed = Clock.fixed(Instant.parse("2027-03-15T00:00:00Z"), java.time.ZoneOffset.UTC);
        try (var context = new SourceContext(fixed, () -> {})) {
            assertEquals(2027, MobileApiSmartphoneSource.currentYear(context));
        }
    }
}
