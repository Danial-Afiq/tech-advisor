package com.springboot.backend.ingestion;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

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
 */
@Component
public class SmartphoneCatalogSink implements IngestionSink {
    private final JdbcTemplate db;

    public SmartphoneCatalogSink(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public boolean supports(IngestionSource source, Payload.Body body) {
        return body instanceof Payload.Specifications && "mobileapi-smartphone".equals(source.sourceId());
    }

    @Override
    public Result accept(String runId, Payload payload) {
        var spec = (Payload.Specifications) payload.body();

        Long productId = db.queryForObject(
                "INSERT INTO products (brand, model_name, category) VALUES (?, ?, 'SMARTPHONE') "
                        + "ON CONFLICT (brand, model_name) DO UPDATE SET updated_at = CURRENT_TIMESTAMP "
                        + "RETURNING id",
                Long.class, spec.brand(), spec.modelName());

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

        return Result.ACCEPTED;
    }

    private static Integer intOrNull(BigDecimal value) {
        return value == null ? null : value.intValue();
    }
}
