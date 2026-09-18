package com.springboot.backend.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Ticket 1.2 — smartphone specifications from MobileAPI.dev (https://mobileapi.dev/docs/).
 *
 * NOT added to ingestion.enabled-sources yet, and not meant to be until a real
 * product-catalogue sink exists: 1.1 only shipped SimulationSink behind the
 * ingestion-demo profile ("No extra domain tables or product schema are
 * created" — docs/ingestion.md). This adapter is fetch + translate only; being
 * a registered bean does not mean it runs (see SourceRegistry) — enabling it
 * live before a sink exists would fail loudly by design rather than silently.
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
        JsonNode list = fetchList(context);
        JsonNode devices = list.get("devices");
        if (devices == null || !devices.isArray()) return;

        int processed = 0;
        for (JsonNode device : devices) {
            if (processed >= DEVICE_LIMIT) break;

            String deviceId = text(device, "id");
            if (deviceId == null) continue;

            Map<String, BigDecimal> values = new LinkedHashMap<>();
            Map<String, String> units = new LinkedHashMap<>();
            putIfPresent(values, units, "ram", MobileApiFieldExtractor.ramGb(text(device, "hardware")), "GB");
            putIfPresent(values, units, "storage", MobileApiFieldExtractor.storageGb(text(device, "storage")), "GB");
            putIfPresent(values, units, "battery", MobileApiFieldExtractor.batteryMah(text(device, "battery_capacity")), "mAh");
            putIfPresent(values, units, "camera", MobileApiFieldExtractor.cameraMp(text(device, "camera")), "MP");

            if (!values.isEmpty()) {
                output.accept(new Payload(sourceId(), deviceId, context.now(),
                        new Payload.Specifications(deviceId, values, units)));
            }

            processed++;
        }
    }

    private JsonNode fetchList(SourceContext context) throws Exception {
        String key = URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        URI uri = year.isBlank()
                ? URI.create(BASE + "?limit=" + DEVICE_LIMIT + "&key=" + key)
                : URI.create(BASE + "by-year/?year=" + URLEncoder.encode(year, StandardCharsets.UTF_8) + "&key=" + key);
        return json.readTree(context.get(uri));
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static void putIfPresent(Map<String, BigDecimal> values, Map<String, String> units,
            String key, Optional<BigDecimal> value, String unit) {
        value.ifPresent(v -> { values.put(key, v); units.put(key, unit); });
    }
}
