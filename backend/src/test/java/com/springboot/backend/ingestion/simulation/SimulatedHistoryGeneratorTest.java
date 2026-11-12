package com.springboot.backend.ingestion.simulation;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SimulatedHistoryGeneratorTest {

    private final SimulatedHistoryGenerator generator = new SimulatedHistoryGenerator();

    @Test
    void sameSeedAndInputsProduceTheSameOrderedBoundedHistory() {
        var config = new SimulatedHistoryGenerator.Config(
                Instant.parse("2025-01-01T00:00:00Z"),
                12,
                Duration.ofDays(30),
                2037L,
                new BigDecimal("0.15"),
                new BigDecimal("0.03"));
        var baselines = List.of(
                new SimulatedHistoryGenerator.BenchmarkBaseline(
                        "Geekbench 6 Multi-Core", new BigDecimal("8000"), "points", true),
                new SimulatedHistoryGenerator.BenchmarkBaseline(
                        "Render latency", new BigDecimal("25"), "ms", false));

        var first = generator.generate(new BigDecimal("1000.00"), baselines, config);
        var second = generator.generate(new BigDecimal("1000.00"), baselines, config);

        assertEquals(first, second);
        assertEquals(12, first.observations().size());
        for (int index = 0; index < first.observations().size(); index++) {
            var observation = first.observations().get(index);
            assertEquals(config.startAt().plus(config.interval().multipliedBy(index)), observation.observedAt());
            assertTrue(observation.price().compareTo(new BigDecimal("850.00")) >= 0);
            assertTrue(observation.price().compareTo(new BigDecimal("1150.00")) <= 0);
            assertEquals(2, observation.benchmarks().size());
            assertWithinPercent(observation.benchmarks().get(0).score(), new BigDecimal("8000"), "0.03");
            assertWithinPercent(observation.benchmarks().get(1).score(), new BigDecimal("25"), "0.03");
        }
    }

    @Test
    void rejectsUnsafeCountsAndVariationBounds() {
        var baseline = List.of(new SimulatedHistoryGenerator.BenchmarkBaseline(
                "benchmark", BigDecimal.TEN, "points", true));

        assertThrows(IllegalArgumentException.class, () -> generator.generate(
                BigDecimal.TEN, baseline,
                new SimulatedHistoryGenerator.Config(
                        Instant.EPOCH, 1, Duration.ofDays(1), 1,
                        BigDecimal.ZERO, BigDecimal.ZERO)));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(
                BigDecimal.TEN, baseline,
                new SimulatedHistoryGenerator.Config(
                        Instant.EPOCH, 2, Duration.ofDays(1), 1,
                        new BigDecimal("0.31"), BigDecimal.ZERO)));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(
                BigDecimal.TEN, baseline,
                new SimulatedHistoryGenerator.Config(
                        Instant.EPOCH, 2, Duration.ofDays(1), 1,
                        BigDecimal.ZERO, new BigDecimal("0.051"))));
    }

    @Test
    void supportsPriceOnlyOrBenchmarkOnlyProducts() {
        var config = new SimulatedHistoryGenerator.Config(
                Instant.parse("2025-01-01T00:00:00Z"),
                2,
                Duration.ofDays(30),
                7L,
                new BigDecimal("0.10"),
                new BigDecimal("0.02"));
        var benchmark = new SimulatedHistoryGenerator.BenchmarkBaseline(
                "3DMark", new BigDecimal("10000"), "points", true);

        var priceOnly = generator.generate(new BigDecimal("900"), List.of(), config);
        assertTrue(priceOnly.observations().stream().allMatch(row -> row.price() != null));
        assertTrue(priceOnly.observations().stream().allMatch(row -> row.benchmarks().isEmpty()));

        var benchmarkOnly = generator.generate(null, List.of(benchmark), config);
        assertTrue(benchmarkOnly.observations().stream().allMatch(row -> row.price() == null));
        assertTrue(benchmarkOnly.observations().stream().allMatch(row -> row.benchmarks().size() == 1));
    }

    private static void assertWithinPercent(BigDecimal actual, BigDecimal baseline, String percent) {
        BigDecimal delta = baseline.multiply(new BigDecimal(percent));
        assertTrue(actual.compareTo(baseline.subtract(delta)) >= 0, actual + " below lower bound");
        assertTrue(actual.compareTo(baseline.add(delta)) <= 0, actual + " above upper bound");
    }
}
