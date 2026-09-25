package com.springboot.backend.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One benchmark observation for one product (AGENTS.md §14.8).
 *
 * <p>{@code higherIsBetter} is the reason this table cannot be compared naively.
 * Some benchmarks score throughput, where more is better; others score latency
 * or a completion time, where less is. Comparing two products without applying
 * the flag silently inverts the result for the latter, and the LLM must never
 * be asked to infer the direction itself (§8.2).
 */
@Entity
@Table(name = "benchmark_results")
public class BenchmarkResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "benchmark_name", nullable = false)
    private String benchmarkName;

    @Column(nullable = false)
    private BigDecimal score;

    private String unit;

    @Column(name = "higher_is_better", nullable = false)
    private boolean higherIsBetter;

    private String source;

    /** The source's observation time, not our ingest time. */
    @Column(name = "observed_at", nullable = false)
    private OffsetDateTime observedAt;

    protected BenchmarkResult() {}

    /** For seeds and tests; ingestion writes this table in production. */
    public BenchmarkResult(
            Long productId, String benchmarkName, BigDecimal score,
            String unit, boolean higherIsBetter, String source, OffsetDateTime observedAt) {
        this.productId = productId;
        this.benchmarkName = benchmarkName;
        this.score = score;
        this.unit = unit;
        this.higherIsBetter = higherIsBetter;
        this.source = source;
        this.observedAt = observedAt;
    }

    public Long getId() { return id; }
    public Long getProductId() { return productId; }
    public String getBenchmarkName() { return benchmarkName; }
    public BigDecimal getScore() { return score; }
    public String getUnit() { return unit; }
    public boolean isHigherIsBetter() { return higherIsBetter; }
    public String getSource() { return source; }
    public OffsetDateTime getObservedAt() { return observedAt; }
}
