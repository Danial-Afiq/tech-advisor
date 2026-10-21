package com.springboot.backend.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springboot.backend.ingestion.core.IngestionSource;
import com.springboot.backend.ingestion.core.Payload;
import com.springboot.backend.ingestion.core.SourceContext;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
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
 *
 * Bugfix (ingestion_cursors, V12): the default/automatic mode used to call the
 * exact same URL on every run — no page parameter existed at all — so a
 * scheduled run never discovered anything beyond whatever the first page
 * happened to be; confirmed live that the default listing isn't even
 * "newest first," so nothing short of a human manually changing
 * sources.mobileapi.year ever moved coverage at all. Default mode (year
 * unset) now sweeps page-by-page through the current calendar year's
 * by-year listing, one page per run (still exactly 1 HTTP request), tracked
 * in ingestion_cursors. A full sweep restarts from page 1 automatically -
 * picking up anything MobileAPI has added to that year since - and a cursor
 * from a year that no longer matches "now" resets to page 1 of the new
 * year the moment the calendar rolls over, so new-year releases start
 * appearing without any manual step. sources.mobileapi.year, when
 * explicitly set, keeps the original single fixed-page behaviour unchanged —
 * a deliberate manual override (e.g. for a demo pinned to one year), not
 * the path this bug was in.
 */
@Component
public class MobileApiSmartphoneSource implements IngestionSource {
    private static final String BASE = "https://api.mobileapi.dev/devices/";
    private static final int DEVICE_LIMIT = 10;

    private final String year;
    private final String apiKey;
    private final JdbcTemplate db;
    private final ObjectMapper json = new ObjectMapper();

    public MobileApiSmartphoneSource(
            @Value("${sources.mobileapi.year:}") String year,
            @Value("${sources.mobileapi.api-key:}") String apiKey,
            JdbcTemplate db) {
        this.year = year;
        this.apiKey = apiKey;
        this.db = db;
    }

    /** The persisted {@code ingestion_cursors.state} shape for this source. */
    record Cursor(int year, int page) {}

    @Override
    public String sourceId() {
        return "mobileapi-smartphone";
    }

    /**
     * Runs before the default priority(). Deliberate, not incidental: this is
     * the sole source of truth for products/phone rows (AGENTS.md 17.1.1),
     * so anything that reads the catalogue in the same run - SearchAPI's
     * untargeted product pick, for one - must see this source's writes
     * first, not depend on alphabetical sourceId ordering happening to agree.
     */
    @Override
    public int priority() {
        return 10;
    }

    @Override
    public void ingest(SourceContext context, Consumer<Payload> output) throws Exception {
        if (apiKey.isBlank()) return;
        context.check();
        if (!year.isBlank()) {
            // Explicit manual override: unchanged single fixed-page behaviour.
            for (JsonNode device : devicesIn(fetchList(context, year, null))) {
                toPayload(sourceId(), context.now(), device).ifPresent(output::accept);
            }
            return;
        }
        int currentYear = context.now().atZone(ZoneOffset.UTC).getYear();
        Cursor cursor = loadCursor(currentYear);
        JsonNode page = fetchList(context, Integer.toString(cursor.year()), cursor.page());
        for (JsonNode device : devicesIn(page)) {
            toPayload(sourceId(), context.now(), device).ifPresent(output::accept);
        }
        boolean hasNext = page.path("has_next").asBoolean(false);
        saveCursor(advance(cursor, currentYear, hasNext));
    }

    /**
     * Pure cursor-advance rule, split out for direct testability (no DB, no network):
     * - a cursor whose year no longer matches "now" resets to page 1 of the new year
     *   immediately, so the calendar rolling over alone starts surfacing that year's
     *   releases, no manual step required;
     * - otherwise, page forward while the current year's listing has more pages;
     * - a completed sweep (no more pages) restarts at page 1 of the same year rather
     *   than stopping - the only way a newly-added device on an earlier page of a
     *   year already fully swept is ever noticed.
     */
    static Cursor advance(Cursor cursor, int currentYear, boolean hasNext) {
        if (cursor.year() != currentYear) return new Cursor(currentYear, 1);
        return hasNext ? new Cursor(cursor.year(), cursor.page() + 1) : new Cursor(cursor.year(), 1);
    }

    // Package-private (not private) so a real-Postgres test can exercise the actual SQL -
    // the same reasoning SmartphoneCatalogSink's DB-touching helpers follow.
    Cursor loadCursor(int currentYear) {
        List<Cursor> rows = db.query(
                "SELECT state->>'year' AS year, state->>'page' AS page FROM ingestion_cursors WHERE source_id = ?",
                (rs, n) -> new Cursor(rs.getInt("year"), rs.getInt("page")), sourceId());
        Cursor stored = rows.isEmpty() ? null : rows.getFirst();
        // A missing row, or one left over from a previous year, starts fresh at page 1 -
        // same rule as advance()'s year-rollover case, applied once up front too.
        return stored == null || stored.year() != currentYear ? new Cursor(currentYear, 1) : stored;
    }

    void saveCursor(Cursor cursor) {
        db.update(
                """
                INSERT INTO ingestion_cursors (source_id, state, updated_at)
                VALUES (?, jsonb_build_object('year', ?, 'page', ?), CURRENT_TIMESTAMP)
                ON CONFLICT (source_id) DO UPDATE SET
                    state = EXCLUDED.state, updated_at = EXCLUDED.updated_at
                """,
                sourceId(), cursor.year(), cursor.page());
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
        // Ticket 1.8: the full tier list ("256GB, 512GB, 1TB"), separate from values.get
        // ("storage")'s single base-tier figure above - confirmed live this is a free-text
        // list MobileAPI gives per model, not separate records (AGENTS.md 14.5a).
        var storageOptions = MobileApiFieldExtractor.storageOptionsGb(text(device, "storage"));

        if (values.isEmpty()) return Optional.empty();
        return Optional.of(new Payload(sourceId, deviceId, observedAt,
                new Payload.Specifications(deviceId, brand, modelName, chipset, values, units, storageOptions)));
    }

    /**
     * {@code page} null means the original manual-override shape (no page parameter at
     * all, whatever {@code yearFilter} resolves to server-side); non-null pages through
     * the by-year listing explicitly, which is what the cursor-driven default mode uses.
     */
    private JsonNode fetchList(SourceContext context, String yearFilter, Integer page) throws Exception {
        String key = URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        URI uri = page == null
                ? (yearFilter.isBlank()
                        ? URI.create(BASE + "?limit=" + DEVICE_LIMIT + "&key=" + key)
                        : URI.create(BASE + "by-year/?year=" + URLEncoder.encode(yearFilter, StandardCharsets.UTF_8) + "&key=" + key))
                : URI.create(BASE + "by-year/?year=" + URLEncoder.encode(yearFilter, StandardCharsets.UTF_8)
                        + "&limit=" + DEVICE_LIMIT + "&page=" + page + "&key=" + key);
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
