package com.springboot.backend.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Ticket 1.2 — smartphone specifications from MobileAPI.dev (https://mobileapi.dev/docs/).
 *
 * A real sink now exists (SmartphoneCatalogSink, writing to the canonical
 * products/phone tables from V6__create_sprint_1_schema.sql) — still NOT
 * added to ingestion.enabled-sources, because sources.mobileapi.api-key
 * hasn't actually been provisioned yet, not because of a missing sink
 * anymore. Being a registered bean does not mean it runs (see
 * SourceRegistry); with apiKey blank, ingest() is a safe no-op (see below).
 *
 * Budget: exactly 1 HTTP request per run — the list endpoint already carries
 * every field this ticket needs (RAM/chipset via "hardware", storage, battery
 * capacity, camera) for up to DEVICE_LIMIT devices in one response, so there's
 * no per-device follow-up call. Refresh rate and price would each need a
 * separate per-device endpoint and aren't part of this ticket's AC — dropped
 * for now rather than fetched for some devices and not others.
 * MobileApiFieldExtractor.refreshRateHz/price stay implemented and tested for
 * whenever that's revisited.
 *
 * Auth: MobileAPI.dev supports both a header and a `?key=` query parameter.
 * SourceContext.get(URI) does not let adapters set custom headers, so this
 * uses the query-parameter form deliberately, not as a workaround of last resort.
 */
@Component
public class MobileApiSmartphoneSource implements IngestionSource {
    private static final String BASE = "https://api.mobileapi.dev/devices/";
    private static final int DEVICE_LIMIT = 10;

    private final String year;
    private final String apiKey;
    private final ObjectMapper json = new ObjectMapper();

    public MobileApiSmartphoneSource(
            @Value("${sources.mobileapi.year:}") String year,
            @Value("${sources.mobileapi.api-key:}") String apiKey) {
        this.year = year;
        this.apiKey = apiKey;
    }

    @Override
    public String sourceId() {
        return "mobileapi-smartphone";
    }

    @Override
    public void ingest(SourceContext context, Consumer<Payload> output) throws Exception {
        if (apiKey.isBlank()) return;
        context.check();
        for (JsonNode device : devicesIn(fetchList(context))) {
            toPayload(sourceId(), context.now(), device).ifPresent(output::accept);
        }
    }

    /**
     * Pulls out the "devices" array and caps it at DEVICE_LIMIT - also pure,
     * also split out purely for direct testability (missing/non-array
     * "devices" field, respecting the limit on a longer list).
     */
    static List<JsonNode> devicesIn(JsonNode list) {
        JsonNode devices = list.get("devices");
        if (devices == null || !devices.isArray()) return List.of();
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode device : devices) {
            if (result.size() >= DEVICE_LIMIT) break;
            result.add(device);
        }
        return result;
    }

    /**
     * Pure device-JsonNode -> Payload transform, no network - split out from
     * {@link #ingest} specifically so it's directly unit-testable against
     * fixture JSON. {@link #ingest}/{@link #fetchList} are the only parts of
     * this class that still need a real HTTP call to exercise.
     */
    static Optional<Payload> toPayload(String sourceId, Instant observedAt, JsonNode device) {
        String deviceId = text(device, "id");
        // deviceId doubles as externalId - with no id at all there's no way to identify or
        // route the record, so this case alone is skipped before emission.
        if (deviceId == null) return Optional.empty();

        // Ticket AC: model name always non-null; a record missing it must be rejected and
        // counted as an error in the run's system_log tally, not silently dropped here. So a
        // missing brand/modelName still gets emitted - Payload.validate() rejects it and the
        // orchestrator counts that rejection (PayloadTests/IngestionIntegrationTests cover the
        // validate()/error-counting halves of this).
        String modelName = text(device, "name");
        String brand = text(device, "manufacturer_name");

        String hardware = text(device, "hardware");
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        Map<String, String> units = new LinkedHashMap<>();
        putIfPresent(values, units, "ram", MobileApiFieldExtractor.ramGb(hardware), "GB");
        putIfPresent(values, units, "storage", MobileApiFieldExtractor.storageGb(text(device, "storage")), "GB");
        putIfPresent(values, units, "battery", MobileApiFieldExtractor.batteryMah(text(device, "battery_capacity")), "mAh");
        putIfPresent(values, units, "camera", MobileApiFieldExtractor.cameraMp(text(device, "camera")), "MP");
        String chipset = MobileApiFieldExtractor.chipset(hardware).orElse(null);

        if (values.isEmpty()) return Optional.empty();
        return Optional.of(new Payload(sourceId, deviceId, observedAt,
                new Payload.Specifications(deviceId, brand, modelName, chipset, values, units)));
    }

    private JsonNode fetchList(SourceContext context) throws Exception {
        String key = URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        URI uri = year.isBlank()
                ? URI.create(BASE + "?limit=" + DEVICE_LIMIT + "&key=" + key)
                : URI.create(BASE + "by-year/?year=" + URLEncoder.encode(year, StandardCharsets.UTF_8) + "&key=" + key);
        return json.readTree(context.get(uri));
    }

    static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static void putIfPresent(Map<String, BigDecimal> values, Map<String, String> units,
            String key, Optional<BigDecimal> value, String unit) {
        value.ifPresent(v -> { values.put(key, v); units.put(key, unit); });
    }
}
