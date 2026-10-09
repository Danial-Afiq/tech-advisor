package com.springboot.backend.ingestion;

import com.springboot.backend.ingestion.core.IngestionSink;
import com.springboot.backend.ingestion.core.IngestionSource;
import com.springboot.backend.ingestion.core.Payload;
import com.springboot.backend.marketevent.MarketEventService;
import com.springboot.backend.marketevent.MarketEventType;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ticket 1.2 — persists smartphone Specifications payloads into the real
 * catalogue schema (V6__create_sprint_1_schema.sql: products + phone).
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
        db.update(
                "INSERT INTO phone (product_id, chipset, ram_gb, storage_gb, battery_mah) "
                        + "VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (product_id) DO UPDATE SET "
                        + "chipset = COALESCE(EXCLUDED.chipset, phone.chipset), "
                        + "ram_gb = COALESCE(EXCLUDED.ram_gb, phone.ram_gb), "
                        + "storage_gb = COALESCE(EXCLUDED.storage_gb, phone.storage_gb), "
                        + "battery_mah = COALESCE(EXCLUDED.battery_mah, phone.battery_mah)",
                productId, spec.chipset(),
                intOrNull(v.get("ram")), intOrNull(v.get("storage")), intOrNull(v.get("battery")));

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

    private static Integer intOrNull(BigDecimal value) {
        return value == null ? null : value.intValue();
    }
}
