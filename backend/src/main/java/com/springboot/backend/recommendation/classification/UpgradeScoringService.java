package com.springboot.backend.recommendation.classification;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Collapses a spec comparison into one 0-1 upgrade score, where 1.0 is a
 * strong upgrade recommendation and 0.0 is not recommended.
 *
 * <p>Deterministic and free of I/O: the same comparison, priorities and
 * {@link ScoringSettings} always produce the same score, which is what makes
 * {@code scoring_version} meaningful and old recommendations reproducible.
 *
 * <p>The shape of the calculation:
 * <ol>
 *   <li>each spec normalises to a signed contribution in {@code [-1, 1]}
 *       against its own improvement cap, so units never mix;</li>
 *   <li>specs sharing a factor average together, so a factor with four
 *       measurable specs does not outvote one with a single spec;</li>
 *   <li>each measured factor's priority divided by the priority sum is its
 *       weight; improvement times weight contributes to the final score.</li>
 * </ol>
 *
 * <p>Missing/all-zero priorities use equal weights. Unknown keys are ignored;
 * invalid values use the configured missing priority and integers are clamped
 * to the configured scale. Unmeasurable factors never dilute measured ones.
 */
@Service
public class UpgradeScoringService {

    private final ScoringSettings settings;

    public UpgradeScoringService(ScoringSettings settings) {
        this.settings = settings;
    }

    /**
     * @param comparison the finished spec comparison
     */
    public UpgradeScore score(SpecComparison comparison) {
        return score(comparison, Map.of());
    }

    public UpgradeScore score(SpecComparison comparison, Map<String, ?> priorities) {
        Map<String, List<ScoredSpec>> byFactor = new LinkedHashMap<>();
        for (ScoredSpec spec : comparison.scored()) {
            byFactor.computeIfAbsent(spec.factor(), key -> new ArrayList<>()).add(spec);
        }

        Map<String, Integer> effective = new LinkedHashMap<>();
        for (String factor : Factors.ALL) {
            if (byFactor.containsKey(factor)) {
                effective.put(factor, priority(priorities == null ? null : priorities.get(factor)));
            }
        }
        // A profile can select only unmeasurable factors. It still needs a
        // defined score, just like an empty or all-zero profile.
        if (priorities == null || priorities.keySet().stream().noneMatch(effective::containsKey)
                || effective.values().stream().allMatch(value -> value == 0)) {
            effective.replaceAll((factor, value) -> settings.defaultPriority());
        }
        double prioritySum = effective.values().stream().mapToDouble(Integer::doubleValue).sum();

        Map<String, UpgradeScore.FactorScore> factorScores = new LinkedHashMap<>();
        double total = 0;

        for (Map.Entry<String, Integer> entry : effective.entrySet()) {
            List<ScoredSpec> specs = byFactor.get(entry.getKey()).stream()
                    .sorted(Comparator.comparing(ScoredSpec::spec)).toList();

            double improvement = specs.stream()
                    .mapToDouble(ScoredSpec::contribution)
                    .average()
                    .orElse(0.0);
            double weight = entry.getValue() / prioritySum;
            double contribution = improvement * weight;

            factorScores.put(
                    entry.getKey(),
                    new UpgradeScore.FactorScore(entry.getValue(), weight, improvement, contribution,
                            specs.stream().map(ScoredSpec::spec).toList()));

            total += contribution;
        }

        double coverage = coverage(byFactor.keySet(), comparison.unmeasurableFactors());

        // An empty comparison and a comparison showing no change both average to
        // zero, and they mean completely different things. Coverage is what
        // separates "nothing improved" from "we could not tell", and only the
        // first is an honest NO_MEANINGFUL_CHANGE.
        boolean sufficient = !factorScores.isEmpty() && coverage >= settings.minSpecCoverage();
        if (!sufficient) {
            return new UpgradeScore(0.0, false, factorScores, List.of(), coverage);
        }

        // Net-negative candidates clamp to zero rather than going below it. A
        // regression and a non-event are both simply not upgrades, and the
        // breakdown still records which factors went backwards. The upper
        // clamp is defensive only: a weighted sum of factor improvements already
        // bounded to [-1, 1] cannot exceed 1.0 except by floating-point rounding.
        double score = round(Math.max(0.0, Math.min(1.0, total)));

        return new UpgradeScore(score, true, factorScores, decidingFactors(factorScores), coverage);
    }

    private int priority(Object raw) {
        if (raw instanceof Number number) {
            try {
                // Fractional, NaN and infinite priorities are invalid; do not
                // silently truncate them or overflow large legacy integers.
                BigInteger value = new BigDecimal(number.toString()).toBigIntegerExact();
                return value.max(BigInteger.valueOf(settings.priorityMin()))
                        .min(BigInteger.valueOf(settings.priorityMax())).intValueExact();
            } catch (ArithmeticException | NumberFormatException ignored) {
                // Defined fallback for malformed legacy JSONB values.
            }
        }
        return settings.missingPriority();
    }

    /**
     * How many of the scorable factors could actually be measured.
     *
     * <p>Measured against every factor with at least one spec column, not all
     * twelve - judging against all twelve would permanently fail every
     * comparison, since every category leaves some factors with no measurable
     * column at all (see {@link CategorySpecMapping}).
     */
    private double coverage(Set<String> measuredFactors, List<String> unmeasurable) {
        List<String> expected = Factors.ALL.stream()
                .filter(f -> !unmeasurable.contains(f))
                .toList();

        if (expected.isEmpty()) {
            return 0.0;
        }
        long measured = expected.stream().filter(measuredFactors::contains).count();
        return (double) measured / expected.size();
    }

    /**
     * The factors that actually drove the verdict, strongest influence first.
     *
     * <p>Influence is the size of the weighted contribution.
     * Negative contributors are included: a factor that went backwards is part
     * of why the verdict came out where it did.
     */
    private List<String> decidingFactors(Map<String, UpgradeScore.FactorScore> factorScores) {
        return factorScores.entrySet().stream()
                .filter(entry -> Math.abs(entry.getValue().contribution()) > 0.05)
                .sorted(Comparator.comparingDouble(
                        (Map.Entry<String, UpgradeScore.FactorScore> entry) ->
                                Math.abs(entry.getValue().contribution()))
                        .reversed().thenComparing(Map.Entry::getKey))
                .limit(3)
                .map(Map.Entry::getKey)
                .toList();
    }

    private double round(double value) {
        return BigDecimal.valueOf(value)
                .setScale(settings.scorePrecision(), RoundingMode.HALF_UP)
                .doubleValue();
    }
}
