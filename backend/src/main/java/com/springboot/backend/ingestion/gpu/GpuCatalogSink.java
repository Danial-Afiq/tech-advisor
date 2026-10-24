package com.springboot.backend.ingestion.gpu;

import com.springboot.backend.ingestion.core.IngestionSink;
import com.springboot.backend.ingestion.core.IngestionSource;
import com.springboot.backend.ingestion.core.Payload;
import com.springboot.backend.marketevent.MarketEventService;
import com.springboot.backend.marketevent.MarketEventType;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ticket 1.3 — persists one {@link Payload.GpuCatalogBatch} into products + gpu, plus the
 * launch MSRP as a price_history row. The seed loader and TechSpot share this path, so a seeded
 * card goes through exactly the same validation as a scraped one.
 *
 * <p>The batch is one transaction: an exception part-way leaves nothing behind. A card that
 * fails {@link GpuSpecValidator} is not an exception - it is dropped, named in the run summary,
 * and the rest of the batch proceeds.
 *
 * <p>Identity is case-insensitive {@link GpuNames#key}, so re-running the same batch inserts
 * nothing. As with {@code SmartphoneCatalogSink}, a field the source omits never overwrites a
 * known value, and a PRODUCT_LAUNCH event is recorded only the first time a card is inserted -
 * spec-change events are not implemented for any category yet.
 *
 * <p>One {@code GPU_SPEC_INGESTION} system_log row per accepted batch carries the counts and
 * every dropped card. It is written after commit, so a rolled-back batch logs nothing here;
 * the run log records that failure instead.
 */
@Component
public class GpuCatalogSink implements IngestionSink {
    static final String LOG_COMPONENT = "GPU_SPEC_INGESTION";
    static final String CATEGORY_CONFLICT = "CATEGORY_CONFLICT";
    static final String DUPLICATE_IN_BATCH = "DUPLICATE_IN_BATCH";

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final GpuSpecValidator validator;
    private final MarketEventService marketEvents;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    public GpuCatalogSink(JdbcTemplate db, PlatformTransactionManager transactionManager,
                          GpuSpecValidator validator, MarketEventService marketEvents) {
        this.db = db;
        this.tx = new TransactionTemplate(transactionManager);
        this.validator = validator;
        this.marketEvents = marketEvents;
    }

    @Override
    public boolean supports(IngestionSource source, Payload.Body body) {
        return !source.simulation() && body instanceof Payload.GpuCatalogBatch;
    }

    /** Counts for one batch; also the summary row's metadata. */
    record Summary(String runId, String source, int inserted, int updated, int unchanged,
                   List<Map<String, String>> dropped) {}

    @Override
    public Result accept(String runId, Payload payload) {
        var batch = (Payload.GpuCatalogBatch) payload.body();
        Summary summary = tx.execute(status -> upsertAll(runId, batch));
        log(summary);
        return summary.inserted() + summary.updated() == 0 ? Result.DUPLICATE : Result.ACCEPTED;
    }

    private Summary upsertAll(String runId, Payload.GpuCatalogBatch batch) {
        int inserted = 0, updated = 0, unchanged = 0;
        List<Map<String, String>> dropped = new ArrayList<>();
        var seen = new java.util.HashSet<String>();
        for (var spec : batch.gpus()) {
            var violation = validator.violation(spec);
            if (violation.isPresent()) { dropped.add(drop(spec, violation.get())); continue; }
            String brand = GpuNames.brand(spec.brand());
            String model = GpuNames.model(brand, spec.modelName());
            if (!seen.add(GpuNames.key(brand, model))) { dropped.add(drop(spec, DUPLICATE_IN_BATCH)); continue; }
            switch (upsert(batch.source(), brand, model, spec)) {
                case INSERTED -> inserted++;
                case UPDATED -> updated++;
                case UNCHANGED -> unchanged++;
                case CONFLICT -> dropped.add(drop(spec, CATEGORY_CONFLICT));
            }
        }
        return new Summary(runId, batch.source(), inserted, updated, unchanged, dropped);
    }

    private enum Outcome { INSERTED, UPDATED, UNCHANGED, CONFLICT }

    private Outcome upsert(String source, String brand, String model, Payload.GpuSpec spec) {
        List<Map<String, Object>> existing = db.queryForList("""
                SELECT p.id, p.category, p.release_date, g.base_clock_mhz, g.boost_clock_mhz, g.vram,
                       g.memory_type, g.bus_width, g.tdp_watts, g.architecture,
                       g.product_id IS NOT NULL AS has_gpu
                FROM products p LEFT JOIN gpu g ON g.product_id = p.id
                WHERE lower(p.brand) = lower(?) AND lower(p.model_name) = lower(?)
                """, brand, model);
        Outcome outcome;
        long productId;
        if (existing.isEmpty()) {
            productId = db.queryForObject("""
                    INSERT INTO products (brand, model_name, category, release_date)
                    VALUES (?, ?, 'GPU', ?) RETURNING id
                    """, Long.class, brand, model, spec.releaseDate());
            outcome = Outcome.INSERTED;
        } else {
            Map<String, Object> row = existing.getFirst();
            if (!"GPU".equals(row.get("category"))) return Outcome.CONFLICT;
            productId = ((Number) row.get("id")).longValue();
            outcome = changes(row, spec) ? Outcome.UPDATED : Outcome.UNCHANGED;
            if (outcome == Outcome.UPDATED) db.update("""
                    UPDATE products SET release_date = COALESCE(?, release_date), updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, spec.releaseDate(), productId);
        }
        // Always written: fetched_at and provenance advance even when the values did not change,
        // which is what moves a card to the back of the TechSpot refresh queue.
        db.update("""
                INSERT INTO gpu (product_id, base_clock_mhz, boost_clock_mhz, vram, memory_type, bus_width,
                                 tdp_watts, architecture, source, source_url, fetched_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (product_id) DO UPDATE SET
                    base_clock_mhz = COALESCE(EXCLUDED.base_clock_mhz, gpu.base_clock_mhz),
                    boost_clock_mhz = COALESCE(EXCLUDED.boost_clock_mhz, gpu.boost_clock_mhz),
                    vram = COALESCE(EXCLUDED.vram, gpu.vram),
                    memory_type = COALESCE(EXCLUDED.memory_type, gpu.memory_type),
                    bus_width = COALESCE(EXCLUDED.bus_width, gpu.bus_width),
                    tdp_watts = COALESCE(EXCLUDED.tdp_watts, gpu.tdp_watts),
                    architecture = COALESCE(EXCLUDED.architecture, gpu.architecture),
                    source = EXCLUDED.source,
                    source_url = COALESCE(EXCLUDED.source_url, gpu.source_url),
                    fetched_at = EXCLUDED.fetched_at
                """, productId, spec.baseClockMhz(), spec.boostClockMhz(), spec.vramGb(), spec.memoryType(),
                spec.memoryBusBits(), spec.tdpWatts(), spec.architecture(), source,
                spec.sourceUrl() == null ? null : spec.sourceUrl().toString());
        recordLaunchMsrp(productId, source, spec);
        if (outcome == Outcome.INSERTED) {
            marketEvents.record(new MarketEventService.NewMarketEvent(
                    productId, MarketEventType.PRODUCT_LAUNCH, "New in catalogue: " + brand + " " + model,
                    null, null, null, source));
        }
        return outcome;
    }

    /**
     * Launch MSRP is a historical fact, so it is written once per product and source and never
     * refreshed. It is dated at release so a later live retail price is always the latest one.
     */
    private void recordLaunchMsrp(long productId, String source, Payload.GpuSpec spec) {
        if (spec.launchMsrpUsd() == null) return;
        db.update("""
                INSERT INTO price_history (product_id, price, currency, source, observed_at)
                SELECT ?, ?, 'USD', ?, ?
                WHERE NOT EXISTS (SELECT 1 FROM price_history WHERE product_id = ? AND source = ?)
                """, productId, spec.launchMsrpUsd(), "launch_msrp:" + source,
                Timestamp.from(spec.releaseDate().atStartOfDay(ZoneOffset.UTC).toInstant()),
                productId, "launch_msrp:" + source);
    }

    /** True when a value the source did publish differs from what is stored (COALESCE semantics). */
    private static boolean changes(Map<String, Object> row, Payload.GpuSpec spec) {
        return !Boolean.TRUE.equals(row.get("has_gpu"))
                || differs(row.get("release_date") == null ? null : ((java.sql.Date) row.get("release_date")).toLocalDate(),
                        spec.releaseDate())
                || differs(number(row.get("base_clock_mhz")), spec.baseClockMhz())
                || differs(number(row.get("boost_clock_mhz")), spec.boostClockMhz())
                || differsNumeric((BigDecimal) row.get("vram"), spec.vramGb())
                || differs(row.get("memory_type"), spec.memoryType())
                || differs(number(row.get("bus_width")), spec.memoryBusBits())
                || differs(number(row.get("tdp_watts")), spec.tdpWatts())
                || differs(row.get("architecture"), spec.architecture());
    }
    private static Integer number(Object value) { return value == null ? null : ((Number) value).intValue(); }
    private static boolean differs(Object stored, Object incoming) {
        return incoming != null && !Objects.equals(stored, incoming);
    }
    private static boolean differsNumeric(BigDecimal stored, BigDecimal incoming) {
        return incoming != null && (stored == null || stored.compareTo(incoming) != 0);
    }

    private static Map<String, String> drop(Payload.GpuSpec spec, String rule) {
        var entry = new LinkedHashMap<String, String>();
        entry.put("brand", String.valueOf(spec.brand()));
        entry.put("model", String.valueOf(spec.modelName()));
        entry.put("rule", rule);
        return entry;
    }

    private void log(Summary summary) {
        int dropped = summary.dropped().size();
        db.update("""
                INSERT INTO system_log (component, status, message, metadata)
                VALUES (?, ?, ?, ?::jsonb)
                """, LOG_COMPONENT, dropped == 0 ? "SUCCESS" : "PARTIAL_FAILURE",
                "GPU catalogue batch from %s: %d inserted, %d updated, %d unchanged, %d dropped".formatted(
                        summary.source(), summary.inserted(), summary.updated(), summary.unchanged(), dropped),
                json.writeValueAsString(summary));
    }
}
