package com.springboot.backend.ingestion.techapi;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class TechApiBenchmarkRepository {
    private static final String STATE_COMPONENT = "TECHAPI_BENCHMARK_STATE";
    private final JdbcTemplate db;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    public TechApiBenchmarkRepository(JdbcTemplate db) {
        this.db = db;
    }

    public record PhoneTarget(long productId, String brand, String modelName, String chipset) {}
    public record Batch(List<PhoneTarget> phones, boolean hasMore) {}

    public TechApiIngestionState state() {
        var rows = db.query("""
                SELECT metadata::text
                FROM system_log
                WHERE component = ?
                ORDER BY id DESC
                LIMIT 1
                """, (rs, row) -> json.readValue(rs.getString(1), TechApiIngestionState.class),
                STATE_COMPONENT);
        return rows.isEmpty() ? new TechApiIngestionState() : rows.getFirst();
    }

    public void save(TechApiIngestionState state) {
        int updated = db.update("""
                UPDATE system_log
                SET status = ?, message = ?, metadata = ?::jsonb
                WHERE id = (
                    SELECT id FROM system_log WHERE component = ? ORDER BY id DESC LIMIT 1
                )
                """, state.initialBackfillComplete ? "READY" : "BACKFILLING",
                "TechAPI benchmark ingestion checkpoint", json.writeValueAsString(state),
                STATE_COMPONENT);
        if (updated == 0) db.update("""
                INSERT INTO system_log(component, status, message, metadata)
                VALUES (?, ?, ?, ?::jsonb)
                """, STATE_COMPONENT, state.initialBackfillComplete ? "READY" : "BACKFILLING",
                "TechAPI benchmark ingestion checkpoint", json.writeValueAsString(state));
    }

    public Batch nextPhones(long cursor, int limit) {
        var phones = queryAfter(cursor, limit + 1);
        boolean hasMore = phones.size() > limit;
        return new Batch(phones.stream().limit(limit).toList(), hasMore);
    }

    public List<PhoneTarget> phonesThrough(long maximumProductId) {
        return db.query("""
                SELECT p.id, p.brand, p.model_name, ph.chipset
                FROM products p
                JOIN phone ph ON ph.product_id = p.id
                WHERE p.id <= ?
                  AND p.category = 'SMARTPHONE'
                  AND p.status = 'VERIFIED'
                  AND ph.chipset IS NOT NULL
                  AND btrim(ph.chipset) <> ''
                ORDER BY p.id
                """, PHONE_MAPPER, maximumProductId);
    }

    public List<PhoneTarget> allPhones() {
        return phonesThrough(Long.MAX_VALUE);
    }

    public long maximumEligibleProductId() {
        Long result = db.queryForObject("""
                SELECT coalesce(max(p.id), 0)
                FROM products p
                JOIN phone ph ON ph.product_id = p.id
                WHERE p.category = 'SMARTPHONE'
                  AND p.status = 'VERIFIED'
                  AND ph.chipset IS NOT NULL
                  AND btrim(ph.chipset) <> ''
                """, Long.class);
        return result == null ? 0 : result;
    }

    public boolean hasCompleteCoverage(long productId) {
        Integer result = db.queryForObject("""
                SELECT count(DISTINCT benchmark_name)
                FROM benchmark_results
                WHERE product_id = ?
                  AND benchmark_name IN (?, ?)
                  AND source LIKE 'TechAPI GitHub%'
                """, Integer.class, productId, TechApiBenchmarkSource.GEEKBENCH_SINGLE,
                TechApiBenchmarkSource.GEEKBENCH_MULTI);
        return result != null && result == 2;
    }

    public long observationCount() {
        Long result = db.queryForObject(
                "SELECT count(*) FROM benchmark_results WHERE source LIKE 'TechAPI GitHub%'",
                Long.class);
        return result == null ? 0 : result;
    }

    public long coveredProductCount() {
        Long result = db.queryForObject("""
                SELECT count(DISTINCT product_id)
                FROM benchmark_results
                WHERE source LIKE 'TechAPI GitHub%'
                """, Long.class);
        return result == null ? 0 : result;
    }

    private static final org.springframework.jdbc.core.RowMapper<PhoneTarget> PHONE_MAPPER =
            (rs, row) -> new PhoneTarget(rs.getLong("id"), rs.getString("brand"),
                    rs.getString("model_name"), rs.getString("chipset"));

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
                """, PHONE_MAPPER, cursor, limit);
    }
}
