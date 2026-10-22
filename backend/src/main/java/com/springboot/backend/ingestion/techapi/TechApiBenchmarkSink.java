package com.springboot.backend.ingestion.techapi;

import com.springboot.backend.ingestion.core.IngestionSink;
import com.springboot.backend.ingestion.core.IngestionSource;
import com.springboot.backend.ingestion.core.Payload;
import com.springboot.backend.model.BenchmarkResult;
import com.springboot.backend.repository.BenchmarkResultRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class TechApiBenchmarkSink implements IngestionSink {
    private final BenchmarkResultRepository benchmarks;

    public TechApiBenchmarkSink(BenchmarkResultRepository benchmarks) {
        this.benchmarks = benchmarks;
    }

    @Override
    public boolean supports(IngestionSource source, Payload.Body body) {
        return !source.simulation() && body instanceof Payload.Benchmark;
    }

    @Override
    public Result accept(String runId, Payload payload) {
        var benchmark = (Payload.Benchmark) payload.body();
        long productId;
        try {
            productId = Long.parseLong(benchmark.productReference());
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException(
                    "Payload.Benchmark productReference must be a resolved internal id");
        }

        String provenance = benchmark.provenance() == null
                ? payload.sourceId()
                : benchmark.provenance();
        if (TechApiBenchmarkSource.ID.equals(payload.sourceId())
                && benchmarks.existsByProductIdAndBenchmarkNameAndSourceStartingWith(
                        productId, benchmark.name(), "TechAPI GitHub")) {
            return Result.DUPLICATE;
        }
        var latest = benchmarks.findFirstByProductIdAndBenchmarkNameOrderByObservedAtDescIdDesc(
                productId, benchmark.name());
        if (latest.isPresent()
                && latest.get().getScore().compareTo(benchmark.score()) == 0
                && Objects.equals(latest.get().getUnit(), benchmark.unit())
                && latest.get().isHigherIsBetter() == benchmark.higherIsBetter()
                && Objects.equals(latest.get().getSource(), provenance)) {
            return Result.DUPLICATE;
        }

        benchmarks.save(new BenchmarkResult(
                productId,
                benchmark.name(),
                benchmark.score(),
                benchmark.unit(),
                benchmark.higherIsBetter(),
                provenance,
                OffsetDateTime.ofInstant(payload.observedAt(), ZoneOffset.UTC)));
        return Result.ACCEPTED;
    }
}
