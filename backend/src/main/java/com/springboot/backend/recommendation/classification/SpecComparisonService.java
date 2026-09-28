package com.springboot.backend.recommendation.classification;

import com.springboot.backend.model.BenchmarkResult;
import com.springboot.backend.model.Phone;
import com.springboot.backend.recommendation.AssessRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Turns two spec sheets into finished, direction-corrected numbers.
 *
 * <p>Java owns every figure here because the LLM must never be asked to do
 * arithmetic or to infer whether a bigger benchmark number is better
 * (AGENTS.md §8.2, §28.3). The output feeds both the upgrade score and the
 * {@code computed} block on the wire, so the number the user is shown and the
 * number the model reasons about cannot drift apart.
 */
@Service
public class SpecComparisonService {

    /**
     * @param owned               reference spec sheet from the catalogue
     * @param ownedOverrides      the owner's {@code spec_overrides}, keyed by
     *                            {@code phone} column name. These win over the
     *                            catalogue so one user's actual configuration
     *                            can differ from the shared product record
     *                            without mutating it (AGENTS.md §14.3)
     * @param candidate           candidate spec sheet
     * @param ownedBenchmarks     latest observation per benchmark, owned product
     * @param candidateBenchmarks latest observation per benchmark, candidate
     * @param candidatePrice      latest observed price, or null when unpriced
     * @param budget              the configured budget, or null when unset
     */
    public SpecComparison compare(
            Phone owned,
            Map<String, Object> ownedOverrides,
            Phone candidate,
            List<BenchmarkResult> ownedBenchmarks,
            List<BenchmarkResult> candidateBenchmarks,
            BigDecimal candidatePrice,
            BigDecimal budget,
            String currency) {

        List<ScoredSpec> scored = new ArrayList<>();
        Map<String, AssessRequest.SpecDelta> deltas = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();

        for (SpecFactorCatalog.SpecRule rule : SpecFactorCatalog.NUMERIC_SPECS) {
            Double from = overrideOr(ownedOverrides, rule.spec(), rule.reader().apply(owned));
            Double to = SpecFactorCatalog.toDouble(rule.reader().apply(candidate));

            // A null means "not published", never zero. Scoring it would turn an
            // incomplete spec sheet into a fabricated regression.
            if (from == null || to == null || from == 0.0) {
                skipped.add(rule.spec());
                deltas.put(rule.spec(), new AssessRequest.SpecDelta(from, to, null));
                continue;
            }

            double rawPct = (to - from) / Math.abs(from) * 100.0;
            double deltaPct = rule.higherIsBetter() ? rawPct : -rawPct;

            scored.add(new ScoredSpec(rule.spec(), rule.factor(), from, to, deltaPct, rule.improvementCap()));
            // The wire carries the plain observed change; only scoring applies the
            // direction correction, so the model still sees the real movement.
            deltas.put(rule.spec(), new AssessRequest.SpecDelta(from, to, round1(rawPct)));
        }

        for (SpecFactorCatalog.TextSpecRule rule : SpecFactorCatalog.TEXT_SPECS) {
            String from = rule.reader().apply(owned);
            String to = rule.reader().apply(candidate);
            if (from == null && to == null) {
                continue;
            }
            // Reportable, never scorable: there is no honest percentage
            // difference between two chipset names.
            deltas.put(rule.spec(), new AssessRequest.SpecDelta(from, to, null));
        }

        Double uplift = benchmarkUplift(ownedBenchmarks, candidateBenchmarks, scored, deltas, skipped);
        AssessRequest.Price price = priceAgainstBudget(candidatePrice, budget, currency, scored, deltas, skipped);

        return new SpecComparison(scored, deltas, uplift, price, skipped);
    }

    /**
     * Average uplift across the benchmarks both products actually ran.
     *
     * <p>Only shared benchmark names are comparable: a candidate score on a test
     * the owned device never ran says nothing about the difference between them.
     * {@code higher_is_better} is applied per benchmark, so a latency measurement
     * does not read as a regression when it improves.
     */
    private Double benchmarkUplift(
            List<BenchmarkResult> ownedBenchmarks,
            List<BenchmarkResult> candidateBenchmarks,
            List<ScoredSpec> scored,
            Map<String, AssessRequest.SpecDelta> deltas,
            List<String> skipped) {

        Map<String, BenchmarkResult> ownedByName = new LinkedHashMap<>();
        for (BenchmarkResult result : ownedBenchmarks) {
            ownedByName.putIfAbsent(result.getBenchmarkName(), result);
        }

        List<Double> upliftPercentages = new ArrayList<>();
        double ownedTotal = 0;
        double candidateTotal = 0;

        for (BenchmarkResult candidateResult : candidateBenchmarks) {
            BenchmarkResult ownedResult = ownedByName.get(candidateResult.getBenchmarkName());
            if (ownedResult == null) {
                continue;
            }

            double from = ownedResult.getScore().doubleValue();
            double to = candidateResult.getScore().doubleValue();
            if (from == 0.0) {
                continue;
            }

            double rawPct = (to - from) / Math.abs(from) * 100.0;
            upliftPercentages.add(candidateResult.isHigherIsBetter() ? rawPct : -rawPct);
            ownedTotal += from;
            candidateTotal += to;
        }

        if (upliftPercentages.isEmpty()) {
            skipped.add(SpecFactorCatalog.BENCHMARK_SPEC);
            return null;
        }

        double uplift = upliftPercentages.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        scored.add(new ScoredSpec(
                SpecFactorCatalog.BENCHMARK_SPEC,
                Factors.PERFORMANCE,
                ownedTotal,
                candidateTotal,
                uplift,
                SpecFactorCatalog.BENCHMARK_IMPROVEMENT_CAP));
        deltas.put(
                SpecFactorCatalog.BENCHMARK_SPEC,
                new AssessRequest.SpecDelta(ownedTotal, candidateTotal, round1(uplift)));
        return round1(uplift);
    }

    /**
     * Price, scored as the {@code value} factor.
     *
     * <p>{@code vs_budget} is negative when the candidate is under budget (§9).
     * Headroom is what scores: a candidate leaving a third of the budget unspent
     * is better value than one consuming all of it, and one over budget is a
     * real negative rather than merely neutral.
     */
    private AssessRequest.Price priceAgainstBudget(
            BigDecimal candidatePrice,
            BigDecimal budget,
            String currency,
            List<ScoredSpec> scored,
            Map<String, AssessRequest.SpecDelta> deltas,
            List<String> skipped) {

        if (candidatePrice == null || budget == null || budget.signum() <= 0) {
            skipped.add(SpecFactorCatalog.PRICE_SPEC);
            return candidatePrice == null
                    ? null
                    : new AssessRequest.Price(candidatePrice.doubleValue(), currency, null, null);
        }

        double price = candidatePrice.doubleValue();
        double budgetValue = budget.doubleValue();
        double vsBudget = price - budgetValue;
        double headroomPct = -vsBudget / budgetValue * 100.0;

        scored.add(new ScoredSpec(
                SpecFactorCatalog.PRICE_SPEC,
                Factors.VALUE,
                budgetValue,
                price,
                headroomPct,
                SpecFactorCatalog.PRICE_IMPROVEMENT_CAP));
        deltas.put(
                SpecFactorCatalog.PRICE_SPEC,
                new AssessRequest.SpecDelta(budgetValue, price, round1(headroomPct)));

        return new AssessRequest.Price(price, currency, round1(vsBudget), null);
    }

    /**
     * The owner's override for a spec, falling back to the catalogue value.
     *
     * <p>An override that is present but not a number is ignored rather than
     * failing the whole comparison: {@code spec_overrides} is free-form JSON the
     * user controls, and one malformed entry should cost that spec, not the
     * entire assessment.
     */
    private static Double overrideOr(Map<String, Object> overrides, String spec, Number catalogueValue) {
        if (overrides != null) {
            Object override = overrides.get(spec);
            if (override instanceof Number number) {
                return number.doubleValue();
            }
            if (override instanceof String text) {
                try {
                    return Double.valueOf(text.trim());
                } catch (NumberFormatException ignored) {
                    // fall through to the catalogue value
                }
            }
        }
        return SpecFactorCatalog.toDouble(catalogueValue);
    }

    /** One decimal place, matching the precision the §9 examples use. */
    private static Double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
