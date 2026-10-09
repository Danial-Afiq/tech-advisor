package com.springboot.backend.ingestion;

import com.springboot.backend.ingestion.core.IngestionSink;
import com.springboot.backend.ingestion.core.IngestionSource;
import com.springboot.backend.ingestion.core.Payload;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Ticket 1.8 — the first real writer for `price_history`. Payload.Price existed since
 * ticket 1.1's shared contract but no source ever emitted it and no sink ever accepted it:
 * SearchAPI's untargeted price capture (SearchApiSource) is the first real emitter.
 *
 * Observations accumulate - price_history is a time series (see V6's column comment), so
 * this never dedupes or upserts; every accepted payload is one more row. phone_variant_id is
 * left null when the source couldn't attribute the price to one exact SKU (Payload.Price's
 * variantReference was null) - a model-level observation is still real evidence, not nothing.
 */
@Component
public class PriceHistorySink implements IngestionSink {
    private final JdbcTemplate db;
    public PriceHistorySink(JdbcTemplate db) { this.db = db; }

    @Override public boolean supports(IngestionSource source, Payload.Body body) {
        // SimulationSink already claims every payload from a simulation source (any body
        // type) for its demo receipts - without this exclusion, a simulation source's
        // Payload.Price would match both sinks, which the orchestrator correctly refuses
        // ("exactly one typed sink must accept"). Found by running the full suite, not by
        // inspection: SmartphoneCatalogSink/ReviewBatchSink never collided with it because
        // they're scoped to one specific real sourceId instead.
        return !source.simulation() && body instanceof Payload.Price;
    }

    @Override public Result accept(String runId, Payload payload) {
        var price = (Payload.Price) payload.body();
        long productId;
        Long variantId;
        try {
            productId = Long.parseLong(price.productReference());
            variantId = price.variantReference() == null ? null : Long.parseLong(price.variantReference());
        } catch (NumberFormatException e) {
            // Every current emitter resolves real internal ids before emitting (same
            // discipline ReviewBatchSink's productId relies on) - a non-numeric reference
            // here means a future source emitted an unresolved external key by mistake.
            // Fails the run loudly rather than silently dropping the observation.
            throw new IllegalStateException("Payload.Price productReference/variantReference must be a "
                    + "resolved internal id, not an external reference: " + payload.externalId());
        }
        db.update("""
                INSERT INTO price_history (product_id, phone_variant_id, price, currency, source, observed_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, productId, variantId, price.amount(), price.currency(), payload.sourceId(),
                java.sql.Timestamp.from(payload.observedAt()));
        return Result.ACCEPTED;
    }
}
