package com.springboot.backend.service;

import com.springboot.backend.dto.CatalogueBenchmarkRequest;
import com.springboot.backend.dto.CataloguePriceRequest;
import com.springboot.backend.dto.SmartphonePriceRequest;
import com.springboot.backend.exception.InvalidCatalogueRequestException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Appends admin-entered price and benchmark observations for a catalogue entry
 * being created. Both tables only ever grow (the newest row is what the rest of
 * the system reads), so nothing here updates or deletes an existing row.
 *
 * <p>Runs inside the caller's create transaction, so a bad observation rolls
 * back the new product too.
 */
@Service
public class CatalogueObservationService {

    /** {@code source} stored when the admin leaves it blank. */
    public static final String ADMIN_SOURCE = GpuCatalogueService.ADMIN_SOURCE;

    private final JdbcTemplate jdbc;
    private final Clock clock = Clock.systemUTC();

    public CatalogueObservationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Smartphone prices; storage and/or RAM attach the price to that phone_variants row. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void addSmartphonePrices(long productId, List<SmartphonePriceRequest> prices) {
        if (prices == null) return;
        for (SmartphonePriceRequest p : prices) {
            Long variantId = p.storageGb() == null && p.ramGb() == null
                    ? null
                    : variantId(productId, p.storageGb(), p.ramGb());
            insertPrice(productId, p.price(), p.currency(), p.source(), p.observedOn(), variantId);
        }
    }

    /** Model-level prices, e.g. for a GPU. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void addPrices(long productId, List<CataloguePriceRequest> prices) {
        if (prices == null) return;
        for (CataloguePriceRequest p : prices) {
            insertPrice(productId, p.price(), p.currency(), p.source(), p.observedOn(), null);
        }
    }

    /**
     * Benchmark results. The unit and direction are never asked of the admin:
     * they are copied from the latest stored result with the same name, so a
     * manual row stays comparable with ingested ones (the comparison skips a
     * benchmark whose unit or direction differs). An unknown name is stored as
     * higher-is-better with no unit, the column default.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void addBenchmarks(long productId, List<CatalogueBenchmarkRequest> benchmarks) {
        if (benchmarks == null) return;
        for (CatalogueBenchmarkRequest b : benchmarks) {
            String name = b.benchmarkName().trim();
            List<Map<String, Object>> known = jdbc.queryForList("""
                    SELECT benchmark_name, unit, higher_is_better
                      FROM benchmark_results
                     WHERE lower(benchmark_name) = lower(?)
                     ORDER BY observed_at DESC, id DESC
                     LIMIT 1
                    """, name);
            String storedName = known.isEmpty() ? name : (String) known.get(0).get("benchmark_name");
            String unit = known.isEmpty() ? null : (String) known.get(0).get("unit");
            boolean higherIsBetter = known.isEmpty() || (Boolean) known.get(0).get("higher_is_better");
            jdbc.update("""
                    INSERT INTO benchmark_results
                        (product_id, benchmark_name, score, unit, higher_is_better, source, observed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, productId, storedName, b.score(), unit, higherIsBetter,
                    source(b.source()), observedAt(b.observedOn()));
        }
    }

    private void insertPrice(long productId, BigDecimal price, String currency, String source,
                             LocalDate observedOn, Long variantId) {
        jdbc.update("""
                INSERT INTO price_history (product_id, price, currency, source, observed_at, phone_variant_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, productId, price, currency, source(source), observedAt(observedOn), variantId);
    }

    /**
     * The variant row for this storage/RAM pair, created if missing. Region,
     * chipset and model number stay unknown; the identity constraint treats
     * nulls as equal, so two prices for one pair share one row.
     */
    private long variantId(long productId, Integer storageGb, Integer ramGb) {
        return jdbc.queryForObject("""
                INSERT INTO phone_variants (product_id, storage_gb, ram_gb)
                VALUES (?, ?, ?)
                ON CONFLICT ON CONSTRAINT phone_variants_identity_unique
                DO UPDATE SET updated_at = CURRENT_TIMESTAMP
                RETURNING id
                """, Long.class, productId, storageGb, ramGb);
    }

    /**
     * Midnight UTC of the observed day. One day of slack covers an admin ahead
     * of UTC entering their "today"; anything later would become the "latest"
     * price or result until real time caught up with it.
     */
    private OffsetDateTime observedAt(LocalDate day) {
        if (day.isAfter(LocalDate.now(clock).plusDays(1))) {
            throw new InvalidCatalogueRequestException("An observed date cannot be in the future");
        }
        return day.atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    private static String source(String source) {
        return source == null || source.isBlank() ? ADMIN_SOURCE : source.trim();
    }
}
