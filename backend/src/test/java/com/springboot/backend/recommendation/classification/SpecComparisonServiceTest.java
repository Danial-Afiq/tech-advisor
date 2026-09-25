package com.springboot.backend.recommendation.classification;

import static org.junit.jupiter.api.Assertions.*;

import com.springboot.backend.model.BenchmarkResult;
import com.springboot.backend.model.Phone;
import com.springboot.backend.recommendation.AssessRequest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpecComparisonServiceTest {

    private final SpecComparisonService service = new SpecComparisonService();

    private static Phone phone(long id, int batteryMah, int ramGb, int weightG) {
        return Phone.builder(id)
                .batteryMah(batteryMah)
                .ramGb(ramGb)
                .weightG(weightG)
                .build();
    }

    private static BenchmarkResult benchmark(long productId, String name, double score, boolean higherIsBetter) {
        return new BenchmarkResult(
                productId, name, BigDecimal.valueOf(score), "points", higherIsBetter, "test", OffsetDateTime.now());
    }

    private SpecComparison compare(Phone owned, Phone candidate) {
        return service.compare(owned, Map.of(), candidate, List.of(), List.of(), null, null, "SGD");
    }

    @Test
    void computesSignedPercentageDeltasPerSpec() {
        SpecComparison result = compare(phone(1, 4000, 8, 200), phone(2, 5000, 8, 200));

        AssessRequest.SpecDelta battery = result.specDeltas().get("battery_mah");
        assertEquals(4000.0, battery.current());
        assertEquals(5000.0, battery.candidate());
        assertEquals(25.0, battery.deltaPct());
    }

    @Test
    void lighterIsBetterSoWeightIsInvertedBeforeScoring() {
        // 200g -> 180g is a 10% reduction, which must score as a 10% improvement.
        SpecComparison result = compare(phone(1, 4000, 8, 200), phone(2, 4000, 8, 180));

        ScoredSpec weight = result.scored().stream()
                .filter(s -> s.spec().equals("weight_g"))
                .findFirst()
                .orElseThrow();
        assertTrue(weight.deltaPct() > 0, "a lighter phone must score positively, was " + weight.deltaPct());
        assertEquals(10.0, weight.deltaPct(), 0.001);

        // The wire still carries the real movement, not the corrected sign, so
        // the model is not told the phone got heavier.
        assertEquals(-10.0, result.specDeltas().get("weight_g").deltaPct());
    }

    @Test
    void aMissingValueIsSkippedRatherThanTreatedAsZero() {
        Phone owned = Phone.builder(1L).batteryMah(4000).build();
        Phone candidate = Phone.builder(2L).batteryMah(5000).ramGb(12).build();

        SpecComparison result = compare(owned, candidate);

        assertTrue(result.skippedSpecs().contains("ram_gb"));
        assertTrue(result.scored().stream().noneMatch(s -> s.spec().equals("ram_gb")));
        // Still reported, so the breakdown can say it was unknown.
        assertNull(result.specDeltas().get("ram_gb").deltaPct());
        assertEquals(12.0, result.specDeltas().get("ram_gb").candidate());
    }

    @Test
    void specOverridesWinOverTheCatalogue() {
        // The catalogue says 128GB; this owner actually bought the 512GB model.
        Phone owned = Phone.builder(1L).storageGb(128).build();
        Phone candidate = Phone.builder(2L).storageGb(256).build();

        SpecComparison result = service.compare(
                owned, Map.of("storage_gb", 512), candidate, List.of(), List.of(), null, null, "SGD");

        ScoredSpec storage = result.scored().stream()
                .filter(s -> s.spec().equals("storage_gb"))
                .findFirst()
                .orElseThrow();
        assertEquals(512.0, storage.current());
        assertTrue(storage.deltaPct() < 0, "256GB is a downgrade from 512GB");
    }

    @Test
    void anUnreadableOverrideFallsBackToTheCatalogueValue() {
        Phone owned = Phone.builder(1L).storageGb(128).build();
        Phone candidate = Phone.builder(2L).storageGb(256).build();

        SpecComparison result = service.compare(
                owned, Map.of("storage_gb", "not a number"), candidate, List.of(), List.of(), null, null, "SGD");

        ScoredSpec storage = result.scored().stream()
                .filter(s -> s.spec().equals("storage_gb"))
                .findFirst()
                .orElseThrow();
        assertEquals(128.0, storage.current());
    }

    @Test
    void benchmarkDirectionIsAppliedSoLowerIsBetterDoesNotInvert() {
        Phone owned = phone(1, 4000, 8, 200);
        Phone candidate = phone(2, 4000, 8, 200);

        // A completion-time benchmark: 100s -> 50s is a 50% improvement.
        SpecComparison result = service.compare(
                owned,
                Map.of(),
                candidate,
                List.of(benchmark(1, "render_seconds", 100, false)),
                List.of(benchmark(2, "render_seconds", 50, false)),
                null,
                null,
                "SGD");

        assertEquals(50.0, result.benchmarkUpliftPct(), 0.001);
    }

    @Test
    void onlyBenchmarksBothProductsRanAreCompared() {
        SpecComparison result = service.compare(
                phone(1, 4000, 8, 200),
                Map.of(),
                phone(2, 4000, 8, 200),
                List.of(benchmark(1, "geekbench", 1000, true)),
                List.of(benchmark(2, "antutu", 900000, true)),
                null,
                null,
                "SGD");

        assertNull(result.benchmarkUpliftPct(), "no shared benchmark means no uplift figure");
        assertTrue(result.skippedSpecs().contains(SpecFactorCatalog.BENCHMARK_SPEC));
    }

    @Test
    void underBudgetIsANegativeVsBudgetAndPositiveValue() {
        SpecComparison result = service.compare(
                phone(1, 4000, 8, 200),
                Map.of(),
                phone(2, 4000, 8, 200),
                List.of(),
                List.of(),
                BigDecimal.valueOf(1099),
                BigDecimal.valueOf(1200),
                "SGD");

        // AGENTS.md §9: vs_budget < 0 means the candidate is under budget.
        assertEquals(-101.0, result.price().vsBudget());
        ScoredSpec value = result.scored().stream()
                .filter(s -> s.spec().equals(SpecFactorCatalog.PRICE_SPEC))
                .findFirst()
                .orElseThrow();
        assertTrue(value.deltaPct() > 0, "budget headroom should score positively");
    }

    @Test
    void overBudgetScoresNegativelyRatherThanNeutrally() {
        SpecComparison result = service.compare(
                phone(1, 4000, 8, 200),
                Map.of(),
                phone(2, 4000, 8, 200),
                List.of(),
                List.of(),
                BigDecimal.valueOf(1500),
                BigDecimal.valueOf(1200),
                "SGD");

        assertEquals(300.0, result.price().vsBudget());
        ScoredSpec value = result.scored().stream()
                .filter(s -> s.spec().equals(SpecFactorCatalog.PRICE_SPEC))
                .findFirst()
                .orElseThrow();
        assertTrue(value.contribution() < 0, "over budget is a real negative");
    }

    @Test
    void textSpecsAreReportedButNeverScored() {
        Phone owned = Phone.builder(1L).chipset("Snapdragon 8 Gen 1").batteryMah(4000).build();
        Phone candidate = Phone.builder(2L).chipset("Snapdragon 8 Elite").batteryMah(5000).build();

        SpecComparison result = compare(owned, candidate);

        AssessRequest.SpecDelta chipset = result.specDeltas().get("chipset");
        assertEquals("Snapdragon 8 Gen 1", chipset.current());
        assertEquals("Snapdragon 8 Elite", chipset.candidate());
        assertNull(chipset.deltaPct(), "there is no honest percentage between two chipset names");
        assertTrue(result.scored().stream().noneMatch(s -> s.spec().equals("chipset")));
    }
}
