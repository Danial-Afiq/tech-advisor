package com.springboot.backend.ingestion.searchapi;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** State persistence (system_log-row pattern) plus the round-robin catalogue read this source needs. */
@Repository
public class EbayPriceRepository {
    private static final String STATE_COMPONENT = "SEARCHAPI_EBAY_PRICE_STATE";
    private final JdbcTemplate db;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    public EbayPriceRepository(JdbcTemplate db) { this.db = db; }

    public record Product(long id, String brand, String modelName) {
        public String name() { return brand + " " + modelName; }
    }
    public record Batch(List<Product> products, boolean wrapped) {}

    public EbayPriceIngestionState state() {
        var rows = db.query("""
                SELECT metadata::text FROM system_log WHERE component = ? ORDER BY id DESC LIMIT 1
                """, (rs, row) -> json.readValue(rs.getString(1), EbayPriceIngestionState.class), STATE_COMPONENT);
        return rows.isEmpty() ? new EbayPriceIngestionState() : rows.getFirst();
    }

    public void save(EbayPriceIngestionState state) {
        int updated = db.update("""
                UPDATE system_log SET message = ?, metadata = ?::jsonb
                WHERE id = (SELECT id FROM system_log WHERE component = ? ORDER BY id DESC LIMIT 1)
                """, "eBay price ingestion checkpoint", json.writeValueAsString(state), STATE_COMPONENT);
        if (updated == 0) db.update("""
                INSERT INTO system_log(component, status, message, metadata) VALUES (?, 'READY', ?, ?::jsonb)
                """, STATE_COMPONENT, "eBay price ingestion checkpoint", json.writeValueAsString(state));
    }

    /**
     * Up to {@code limit} VERIFIED smartphones with id &gt; cursor, ordered by id. When fewer
     * than {@code limit} remain (the cursor is near/at the end of the catalogue), wraps around
     * and fills the rest from the start - {@code wrapped} tells the caller a full sweep just
     * completed, so it can reset the persisted cursor to 0 instead of to whatever id the wrapped
     * portion happened to end on (which would skip the very start of the catalogue forever).
     */
    public Batch nextProducts(long cursor, int limit) {
        List<Product> forward = queryAfter(cursor, limit);
        if (forward.size() >= limit) return new Batch(forward, false);
        // cursor itself (not just forward's ids) must stay excluded - it was already the last
        // product processed in a previous run; without this, a batch that wraps immediately
        // after the cursor re-includes that exact product a second time in the same run.
        var seen = forward.stream().map(Product::id).collect(java.util.stream.Collectors.toSet());
        seen.add(cursor);
        List<Product> wrapAround = queryAfter(0, limit).stream()
                .filter(p -> !seen.contains(p.id()))
                .limit(limit - forward.size())
                .toList();
        var combined = new java.util.ArrayList<>(forward);
        combined.addAll(wrapAround);
        return new Batch(List.copyOf(combined), true);
    }

    private List<Product> queryAfter(long cursor, int limit) {
        if (limit <= 0) return List.of();
        return db.query("""
                SELECT id, brand, model_name FROM products
                WHERE status = 'VERIFIED' AND category = 'SMARTPHONE' AND id > ?
                ORDER BY id LIMIT ?
                """, (rs, row) -> new Product(rs.getLong(1), rs.getString(2), rs.getString(3)), cursor, limit);
    }
}
