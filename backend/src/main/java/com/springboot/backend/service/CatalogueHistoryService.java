package com.springboot.backend.service;

import com.springboot.backend.dto.CatalogueBenchmarkResponse;
import com.springboot.backend.dto.CataloguePriceResponse;
import com.springboot.backend.exception.ResourceNotFoundException;
import com.springboot.backend.model.Product;
import com.springboot.backend.repository.ProductRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only price and benchmark history of one catalogue smartphone, newest
 * first, for the admin Edit modal. Rows are never changed here: both tables
 * keep every observation, and the newest one is what the rest of the system
 * uses (the same {@code observed_at DESC, id DESC} order).
 */
@Service
public class CatalogueHistoryService {

    /** Enough history to spot a bad observation without paging. */
    static final int MAX_ROWS = 50;

    private final JdbcTemplate jdbc;
    private final ProductRepository products;

    public CatalogueHistoryService(JdbcTemplate jdbc, ProductRepository products) {
        this.jdbc = jdbc;
        this.products = products;
    }

    @Transactional(readOnly = true)
    public List<CataloguePriceResponse> prices(Long productId) {
        requireSmartphone(productId);
        return jdbc.query("""
                SELECT ph.id, ph.price, ph.currency, ph.source, ph.observed_at,
                       v.storage_gb, v.ram_gb, v.region, v.model_number, v.id AS variant_id
                  FROM price_history ph
                  LEFT JOIN phone_variants v ON v.id = ph.phone_variant_id
                 WHERE ph.product_id = ?
                 ORDER BY ph.observed_at DESC, ph.id DESC
                 LIMIT ?
                """, (rs, row) -> new CataloguePriceResponse(
                rs.getLong("id"),
                rs.getBigDecimal("price"),
                rs.getString("currency"),
                rs.getString("source"),
                rs.getObject("observed_at", OffsetDateTime.class),
                rs.getObject("variant_id") == null ? null : variantLabel(
                        (Integer) rs.getObject("storage_gb"),
                        (Integer) rs.getObject("ram_gb"),
                        rs.getString("region"),
                        rs.getString("model_number"))
        ), productId, MAX_ROWS);
    }

    @Transactional(readOnly = true)
    public List<CatalogueBenchmarkResponse> benchmarks(Long productId) {
        requireSmartphone(productId);
        return jdbc.query("""
                SELECT id, benchmark_name, score, unit, higher_is_better, source, observed_at
                  FROM benchmark_results
                 WHERE product_id = ?
                 ORDER BY observed_at DESC, id DESC
                 LIMIT ?
                """, (rs, row) -> new CatalogueBenchmarkResponse(
                rs.getLong("id"),
                rs.getString("benchmark_name"),
                rs.getBigDecimal("score"),
                rs.getString("unit"),
                rs.getBoolean("higher_is_better"),
                rs.getString("source"),
                rs.getObject("observed_at", OffsetDateTime.class)
        ), productId, MAX_ROWS);
    }

    private void requireSmartphone(Long productId) {
        products.findById(productId)
                .filter(product -> Product.CATEGORY_SMARTPHONE.equals(product.getCategory()))
                .orElseThrow(() -> new ResourceNotFoundException("Smartphone not found"));
    }

    /** "256 GB · 8 GB RAM · SG", or "Variant" when nothing about it is known. */
    static String variantLabel(Integer storageGb, Integer ramGb, String region, String modelNumber) {
        List<String> parts = new ArrayList<>();
        if (storageGb != null) parts.add(storageGb + " GB");
        if (ramGb != null) parts.add(ramGb + " GB RAM");
        if (region != null && !region.isBlank()) parts.add(region);
        if (parts.isEmpty() && modelNumber != null && !modelNumber.isBlank()) parts.add(modelNumber);
        return parts.isEmpty() ? "Variant" : String.join(" · ", parts);
    }
}
