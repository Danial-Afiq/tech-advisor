package com.springboot.backend.ingestion.techapi;

import com.springboot.backend.ingestion.run.RunStore;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TechApiBenchmarkRepository {
    private final JdbcTemplate db;
    private final RunStore runs;

    public TechApiBenchmarkRepository(JdbcTemplate db, RunStore runs) {
        this.db = db;
        this.runs = runs;
    }

    public record PhoneTarget(long productId, String brand, String modelName, String chipset) {}
    public record Batch(List<PhoneTarget> phones, boolean hasMore) {}

    public Batch nextPhones(int limit) {
        long cursor = runs.sourceCursor(TechApiBenchmarkSource.ID);
        var phones = queryAfter(cursor, limit + 1);
        if (phones.isEmpty() && cursor != 0) phones = queryAfter(0, limit + 1);
        boolean hasMore = phones.size() > limit;
        return new Batch(phones.stream().limit(limit).toList(), hasMore);
    }

    public void checkpoint(long productId) {
        runs.sourceCursor(TechApiBenchmarkSource.ID, productId);
    }

    public void restartCycle() {
        runs.sourceCursor(TechApiBenchmarkSource.ID, 0);
    }

    private List<PhoneTarget> queryAfter(long cursor, int limit) {
        return db.query("""
                SELECT p.id, p.brand, p.model_name, ph.chipset
                FROM products p
                JOIN phone ph ON ph.product_id = p.id
                WHERE p.id > ?
                  AND p.category = 'SMARTPHONE'
                  AND p.status = 'VERIFIED'
                  AND ph.chipset IS NOT NULL
                  AND btrim(ph.chipset) <> ''
                ORDER BY p.id
                LIMIT ?
                """, (rs, row) -> new PhoneTarget(
                        rs.getLong("id"), rs.getString("brand"),
                        rs.getString("model_name"), rs.getString("chipset")), cursor, limit);
    }
}
