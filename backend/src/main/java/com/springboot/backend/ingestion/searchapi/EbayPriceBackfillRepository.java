package com.springboot.backend.ingestion.searchapi;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * State persistence (system_log-row pattern, same shape {@link EbayPriceRepository} uses) plus
 * the one-time, non-repeating catalogue read {@link EbayPriceBackfillSource} needs.
 *
 * Deliberately a separate component from {@link EbayPriceRepository}, not a shared query with
 * extra parameters: that source's round-robin is an infinite, every-category sweep with its own
 * cursor that must keep wrapping forever; this one is a bounded, brand-scoped, one-time sweep
 * that must stop advancing for good once every target product has a price. Mixing the two
 * shapes into one cursor would risk either job corrupting the other's progress.
 */
@Repository
public class EbayPriceBackfillRepository {
    private static final String STATE_COMPONENT = "SEARCHAPI_EBAY_BACKFILL_STATE";
    // A product either source has already priced is skipped - the backfill exists to give every
    // target product a first price, not to duplicate one the daily round-robin already captured
    // in the meantime (sources run independently and may both be enabled at once).
    private static final String[] COVERED_SOURCES = {EbayPriceSource.ID, EbayPriceBackfillSource.ID};

    private final JdbcTemplate db;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    public EbayPriceBackfillRepository(JdbcTemplate db) { this.db = db; }

    public record Batch(List<EbayPriceRepository.Product> products, boolean exhausted) {}

    public EbayPriceBackfillState state() {
        var rows = db.query("""
                SELECT metadata::text FROM system_log WHERE component = ? ORDER BY id DESC LIMIT 1
                """, (rs, row) -> json.readValue(rs.getString(1), EbayPriceBackfillState.class), STATE_COMPONENT);
        return rows.isEmpty() ? new EbayPriceBackfillState() : rows.getFirst();
    }

    public void save(EbayPriceBackfillState state) {
        int updated = db.update("""
                UPDATE system_log SET message = ?, metadata = ?::jsonb
                WHERE id = (SELECT id FROM system_log WHERE component = ? ORDER BY id DESC LIMIT 1)
                """, "eBay price backfill checkpoint", json.writeValueAsString(state), STATE_COMPONENT);
        if (updated == 0) db.update("""
                INSERT INTO system_log(component, status, message, metadata) VALUES (?, 'READY', ?, ?::jsonb)
                """, STATE_COMPONENT, "eBay price backfill checkpoint", json.writeValueAsString(state));
    }

    /**
     * Up to {@code limit} VERIFIED products with id &gt; cursor that are either any GPU, or a
     * SMARTPHONE whose brand is in {@code brands}, and that neither this source nor the regular
     * daily {@link EbayPriceSource} has already priced. No wraparound: {@code exhausted} means
     * nothing further matches past this point, not "the catalogue end, go around again" - a
     * one-time backfill that has covered every target product is finished for good.
     */
    public Batch nextProducts(long cursor, int limit, List<String> smartphoneBrands) {
        if (limit <= 0) return new Batch(List.of(), true);
        var products = db.query("""
                SELECT p.id, p.brand, p.model_name, p.category FROM products p
                WHERE p.status = 'VERIFIED' AND p.id > ?
                  AND (p.category = 'GPU' OR (p.category = 'SMARTPHONE' AND p.brand = ANY(?)))
                  AND NOT EXISTS (SELECT 1 FROM price_history ph
                                  WHERE ph.product_id = p.id AND ph.source = ANY(?))
                ORDER BY p.id LIMIT ?
                """, (rs, row) -> new EbayPriceRepository.Product(
                        rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                cursor, smartphoneBrands.toArray(String[]::new), COVERED_SOURCES, limit);
        return new Batch(products, products.size() < limit);
    }

    /**
     * Admin-visible progress: the full target scope (every GPU, every brand-matched
     * smartphone) versus how much of it the cursor has already passed. "Attempted", not
     * "successfully priced" - the cursor advances past a product whether or not a listing was
     * found for it, same as {@link EbayPriceRepository}'s round-robin.
     */
    public long targetCount(List<String> smartphoneBrands) {
        Long total = db.queryForObject("""
                SELECT count(*) FROM products p
                WHERE p.status = 'VERIFIED'
                  AND (p.category = 'GPU' OR (p.category = 'SMARTPHONE' AND p.brand = ANY(?)))
                """, Long.class, (Object) smartphoneBrands.toArray(String[]::new));
        return total == null ? 0 : total;
    }
    public long attemptedCount(long cursor, List<String> smartphoneBrands) {
        Long attempted = db.queryForObject("""
                SELECT count(*) FROM products p
                WHERE p.status = 'VERIFIED' AND p.id <= ?
                  AND (p.category = 'GPU' OR (p.category = 'SMARTPHONE' AND p.brand = ANY(?)))
                """, Long.class, cursor, smartphoneBrands.toArray(String[]::new));
        return attempted == null ? 0 : attempted;
    }

    /** Products this source has actually found and recorded a real eBay listing for. */
    public long observationsCount() {
        Long observed = db.queryForObject(
                "SELECT count(DISTINCT product_id) FROM price_history WHERE source = ?",
                Long.class, EbayPriceBackfillSource.ID);
        return observed == null ? 0 : observed;
    }
}
