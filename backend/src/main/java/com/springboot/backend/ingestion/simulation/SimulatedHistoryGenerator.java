package com.springboot.backend.ingestion.simulation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Pure, seeded generation of bounded demo price and benchmark history. */
@Component
@Profile("ingestion-demo")
public class SimulatedHistoryGenerator {

    static final int MIN_OBSERVATIONS = 2;
    static final int MAX_OBSERVATIONS = 100;
    static final BigDecimal MAX_PRICE_VARIATION = new BigDecimal("0.30");
    static final BigDecimal MAX_BENCHMARK_VARIATION = new BigDecimal("0.05");

    public Generated generate(
            BigDecimal basePrice,
            List<BenchmarkBaseline> benchmarks,
            Config config) {

        validate(basePrice, benchmarks, config);
        SplittableRandom random = new SplittableRandom(config.seed());
        List<Observation> observations = new ArrayList<>(config.observationCount());

        for (int index = 0; index < config.observationCount(); index++) {
            Instant observedAt = config.startAt().plus(config.interval().multipliedBy(index));
            double age = (double) (config.observationCount() - 1 - index)
                    / (config.observationCount() - 1);
            BigDecimal price = basePrice == null
                    ? null
                    : varyPrice(basePrice, config.priceVariation(), age, random);
            List<BenchmarkObservation> generatedBenchmarks = benchmarks.stream()
                    .map(baseline -> new BenchmarkObservation(
                            baseline.name(),
                            varySymmetrically(
                                    baseline.score(), config.benchmarkVariation(), random, 4),
                            baseline.unit(),
                            baseline.higherIsBetter()))
                    .toList();
            observations.add(new Observation(observedAt, price, generatedBenchmarks));
        }

        return new Generated(observations);
    }

    private static BigDecimal varyPrice(
            BigDecimal baseline,
            BigDecimal maximumVariation,
            double age,
            SplittableRandom random) {

        // Older prices tend to be above today's baseline, with small seeded noise.
        double maximum = maximumVariation.doubleValue();
        double trend = maximum * 0.75 * age;
        double noise = (random.nextDouble() * 2.0 - 1.0) * maximum * 0.25;
        double change = Math.max(-maximum, Math.min(maximum, trend + noise));
        return baseline.multiply(BigDecimal.valueOf(1.0 + change))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal varySymmetrically(
            BigDecimal baseline,
            BigDecimal maximumVariation,
            SplittableRandom random,
            int scale) {

        double maximum = maximumVariation.doubleValue();
        double change = (random.nextDouble() * 2.0 - 1.0) * maximum;
        return baseline.multiply(BigDecimal.valueOf(1.0 + change))
                .setScale(scale, RoundingMode.HALF_UP);
    }

    private static void validate(
            BigDecimal basePrice,
            List<BenchmarkBaseline> benchmarks,
            Config config) {

        if (basePrice != null && basePrice.signum() <= 0) {
            throw new IllegalArgumentException("The baseline price must be positive");
        }
        if (benchmarks == null) {
            throw new IllegalArgumentException("Benchmark baselines are required, even when empty");
        }
        if (basePrice == null && benchmarks.isEmpty()) {
            throw new IllegalArgumentException("At least one price or benchmark baseline is required");
        }
        if (benchmarks.stream().anyMatch(b -> b == null || b.name() == null || b.name().isBlank()
                || b.score() == null || b.score().signum() <= 0)) {
            throw new IllegalArgumentException("Benchmark baselines must have a name and positive score");
        }
        if (config == null || config.startAt() == null || config.interval() == null
                || config.interval().isZero() || config.interval().isNegative()
                || config.observationCount() < MIN_OBSERVATIONS
                || config.observationCount() > MAX_OBSERVATIONS) {
            throw new IllegalArgumentException("History configuration is invalid");
        }
        requireRange(config.priceVariation(), MAX_PRICE_VARIATION, "price variation");
        requireRange(config.benchmarkVariation(), MAX_BENCHMARK_VARIATION, "benchmark variation");
    }

    private static void requireRange(BigDecimal value, BigDecimal maximum, String name) {
        if (value == null || value.signum() < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + " must be between 0 and " + maximum);
        }
    }

    public record Config(
            Instant startAt,
            int observationCount,
            Duration interval,
            long seed,
            BigDecimal priceVariation,
            BigDecimal benchmarkVariation) {}

    public record BenchmarkBaseline(
            String name,
            BigDecimal score,
            String unit,
            boolean higherIsBetter) {}

    public record BenchmarkObservation(
            String name,
            BigDecimal score,
            String unit,
            boolean higherIsBetter) {}

    public record Observation(
            Instant observedAt,
            BigDecimal price,
            List<BenchmarkObservation> benchmarks) {
        public Observation {
            benchmarks = List.copyOf(benchmarks);
        }
    }

    public record Generated(List<Observation> observations) {
        public Generated {
            observations = List.copyOf(observations);
        }
    }
}
