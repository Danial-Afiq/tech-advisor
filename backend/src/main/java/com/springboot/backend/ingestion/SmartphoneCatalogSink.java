package com.springboot.backend.ingestion;

import com.springboot.backend.ingestion.core.IngestionSink;
import com.springboot.backend.ingestion.core.IngestionSource;
import com.springboot.backend.ingestion.core.Payload;
import com.springboot.backend.marketevent.MarketEventService;
import com.springboot.backend.marketevent.MarketEventType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ticket 1.2 — persists smartphone Specifications payloads into the real
 * catalogue schema (V6__create_sprint_1_schema.sql: products + phone).
 * Ticket 1.8 adds phone_variants (V8__add_phone_variants.sql) alongside it -
 * phone itself is untouched, kept for whatever still reads it.
 *
 * Scoped to mobileapi-smartphone specifically, not Specifications generally:
 * "exactly one sink must match" (IngestionOrchestrator) means a future GPU
 * source (ticket 1.3, writing to the disjoint gpu subtype table instead)
 * needs its own equally-scoped sink, not a shared one that has to guess
 * which subtype table a given Specifications payload belongs to.
 *
 * "camera" in the values map has no matching column here: phone.camera_specs
 * is TEXT ("48 MP + 12 MP + 12 MP"), not a bare MP count — silently not
 * persisted for now. Extending Payload with a text-values slot for this (and
 * os/ip_rating) is a reasonable next step, not done here to keep this change
 * scoped to what the ticket's AC actually requires (chipset was explicit;
 * camera detail was not).
 *
 * Re-ingest semantics: a field missing on a later run does NOT overwrite a
 * previously-known value (COALESCE against the existing row) — losing data
 * the catalogue once had because a source's free-text field was momentarily
 * unparseable seemed worse than briefly serving a stale value. Revisit if a
 * real "this field is now unknown" signal is ever needed.
 *
 * The first time a product row is inserted, a PRODUCT_LAUNCH market event is
 * recorded in the same transaction, which fires the market-event
 * recommendation trigger once it commits. "Launch" here means "first seen in the
 * catalogue": the initial population of an empty catalogue records one per
 * product. Re-ingesting a known product records nothing - spec and price
 * change detection is not implemented here.
 */
@Component
public class SmartphoneCatalogSink implements IngestionSink {
    static final String SOURCE_ID = "mobileapi-smartphone";

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final MarketEventService marketEvents;

    public SmartphoneCatalogSink(
            JdbcTemplate db, PlatformTransactionManager transactionManager, MarketEventService marketEvents) {
        this.db = db;
        this.tx = new TransactionTemplate(transactionManager);
        this.marketEvents = marketEvents;
    }

    @Override
    public boolean supports(IngestionSource source, Payload.Body body) {
        return body instanceof Payload.Specifications && SOURCE_ID.equals(source.sourceId());
    }

    @Override
    public Result accept(String runId, Payload payload) {
        var spec = (Payload.Specifications) payload.body();
        tx.executeWithoutResult(status -> upsert(spec));
        return Result.ACCEPTED;
    }

    private void upsert(Payload.Specifications spec) {
        // xmax = 0 only on a freshly inserted tuple, so this tells a new product
        // apart from an ON CONFLICT update without a second query.
        Map<String, Object> product = db.queryForMap(
                "INSERT INTO products (brand, model_name, category) VALUES (?, ?, 'SMARTPHONE') "
                        + "ON CONFLICT (brand, model_name) DO UPDATE SET updated_at = CURRENT_TIMESTAMP "
                        + "RETURNING id, (xmax = 0) AS inserted",
                spec.brand(), spec.modelName());
        long productId = ((Number) product.get("id")).longValue();

        Map<String, BigDecimal> v = spec.values();
        Integer ram = intOrNull(v.get("ram")), baseStorage = intOrNull(v.get("storage")),
                battery = intOrNull(v.get("battery"));
        db.update(
                "INSERT INTO phone (product_id, chipset, ram_gb, storage_gb, battery_mah) "
                        + "VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (product_id) DO UPDATE SET "
                        + "chipset = COALESCE(EXCLUDED.chipset, phone.chipset), "
                        + "ram_gb = COALESCE(EXCLUDED.ram_gb, phone.ram_gb), "
                        + "storage_gb = COALESCE(EXCLUDED.storage_gb, phone.storage_gb), "
                        + "battery_mah = COALESCE(EXCLUDED.battery_mah, phone.battery_mah)",
                productId, spec.chipset(), ram, baseStorage, battery);

        // Ticket 1.8: one phone_variants row per known storage tier, same chipset/ram/battery
        // across all of them - MobileAPI gives exactly one of each for the whole model, not per
        // tier (AGENTS.md 14.5a/17.4). No tier list at all (common on older/budget devices) still
        // gets one row from the single base-tier figure above - and even when THAT is also
        // unparseable (storage text empty/garbage on both counts, confirmed live against a real
        // device), still one row with storage_gb null rather than zero. A product is never left
        // with zero variants - the gap this closes. Collections.singletonList, not List.of:
        // List.of rejects a null element, and baseStorage legitimately can be null here.
        List<Integer> tiers = spec.storageOptionsGb().isEmpty()
                ? java.util.Collections.singletonList(baseStorage) : spec.storageOptionsGb();
        for (Integer storageGb : tiers) {
            upsertVariant(productId, spec.chipset(), ram, storageGb, battery);
        }

        if (Boolean.TRUE.equals(product.get("inserted"))) {
            marketEvents.record(new MarketEventService.NewMarketEvent(
                    productId,
                    MarketEventType.PRODUCT_LAUNCH,
                    "New in catalogue: " + spec.brand() + " " + spec.modelName(),
                    null,
                    null,
                    null,
                    SOURCE_ID));
        }
    }

    /**
     * Review feedback (PR #46): the unique key on {@code phone_variants} is an
     * exact match on all five identity columns, so a later ingest where, say,
     * {@code chipset} failed to parse this time ({@code NULL}) would never
     * match an existing row whose chipset IS known - {@code ON CONFLICT}
     * silently inserted a second, incomplete row for what is really the same
     * physical variant. Same failure mode for RAM or storage going unknown on
     * a later run. Fixed by reconciling against existing rows first, treating
     * "unknown on either side" as compatible rather than requiring equality:
     * exactly one compatible row is reused (and only ever filled in via
     * COALESCE, never overwritten with an unknown value); zero compatible
     * rows means this really is a new variant; more than one is genuinely
     * ambiguous and is left alone rather than guessed at. Two rows that
     * differ only by a chipset/model_number that's known on both sides (e.g.
     * a real Snapdragon vs. Exynos release) still stay distinct, since a
     * known-vs-known mismatch is never "compatible."
     */
    private void upsertVariant(long productId, String chipset, Integer ram, Integer storageGb, Integer battery) {
        List<Map<String, Object>> candidates = db.queryForList(
                "SELECT id, chipset, model_number, ram_gb, region, storage_gb FROM phone_variants "
                        + "WHERE product_id = ?",
                productId);
        List<Map<String, Object>> compatible = candidates.stream()
                .filter(row -> compatible(row.get("storage_gb"), storageGb)
                        && compatible(row.get("chipset"), chipset)
                        && compatible(row.get("ram_gb"), ram))
                .toList();

        if (compatible.size() == 1) {
            long id = ((Number) compatible.get(0).get("id")).longValue();
            db.update(
                    "UPDATE phone_variants SET chipset = COALESCE(?, chipset), ram_gb = COALESCE(?, ram_gb), "
                            + "storage_gb = COALESCE(?, storage_gb), "
                            + "battery_mah = COALESCE(?, battery_mah), updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    chipset, ram, storageGb, battery, id);
        } else if (compatible.isEmpty()) {
            db.update(
                    "INSERT INTO phone_variants (product_id, chipset, ram_gb, storage_gb, battery_mah) "
                            + "VALUES (?, ?, ?, ?, ?) "
                            + "ON CONFLICT (product_id, storage_gb, ram_gb, region, chipset, model_number) DO UPDATE SET "
                            + "battery_mah = COALESCE(EXCLUDED.battery_mah, phone_variants.battery_mah), "
                            + "updated_at = CURRENT_TIMESTAMP",
                    productId, chipset, ram, storageGb, battery);
        }
        // compatible.size() > 1: genuinely ambiguous (two or more existing rows are each
        // missing a different identity field) - don't guess which one this ingest is about.
    }

    /** Null-tolerant identity-column comparison: unknown on either side never rules out a match. */
    private static boolean compatible(Object existing, Object incoming) {
        return existing == null || incoming == null || existing.equals(incoming);
    }

    private static Integer intOrNull(BigDecimal value) {
        return value == null ? null : value.intValue();
    }
}
