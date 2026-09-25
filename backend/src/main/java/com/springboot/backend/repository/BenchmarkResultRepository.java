package com.springboot.backend.repository;

import com.springboot.backend.model.BenchmarkResult;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BenchmarkResultRepository extends JpaRepository<BenchmarkResult, Long> {

    /**
     * The most recent observation of each benchmark for one product.
     *
     * <p>Benchmarks accumulate over time the way prices do, so comparing two
     * products means comparing their latest run of the same benchmark, not
     * whichever rows happen to come back first.
     *
     * <p>The {@code id DESC} tiebreaker matches
     * {@link ProductRepository#findCompatibleCandidates} and exists for the same
     * reason: two observations sharing an {@code observed_at} would otherwise
     * resolve arbitrarily and the score would stop being reproducible.
     */
    @Query(value = """
            SELECT DISTINCT ON (benchmark_name) *
            FROM benchmark_results
            WHERE product_id = :productId
            ORDER BY benchmark_name, observed_at DESC, id DESC
            """, nativeQuery = true)
    List<BenchmarkResult> findLatestPerBenchmark(@Param("productId") Long productId);
}
