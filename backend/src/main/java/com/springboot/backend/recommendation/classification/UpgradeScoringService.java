package com.springboot.backend.recommendation.classification;

import java.math.BigDecimal;
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
 * <p>Deterministic and free of I/O: the same comparison and the same
 * {@link ScoringSettings} always produce the same score, which is what makes
 * {@code scoring_version} meaningful and old recommendations reproducible.
 *
 * <p>The shape of the calculation:
 * <ol>
 *   <li>each spec normalises to a signed contribution in {@code [-1, 1]}
 *       against its own improvement cap, so units never mix;</li>
 *   <li>specs sharing a factor average together, so a factor with four
 *       measurable specs does not outvote one with a single spec;</li>
 *   <li>every measured factor carries equal weight, and their plain mean is
 *       the final 0-1 score.</li>
 * </ol>
 *
 * <p>The user's {@code device_preferences.priorities} deliberately play no part
 * here. How they should weight the verdict is still undecided (AGENTS.md
 * §27.5), so for now they only steer retrieval and the model's explanation.
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
        Map<String, List<ScoredSpec>> byFactor = new LinkedHashMap<>();
        for (ScoredSpec spec : comparison.scored()) {
            byFactor.computeIfAbsent(spec.factor(), key -> new ArrayList<>()).add(spec);
        }

        Map<String, UpgradeScore.FactorScore> factorScores = new LinkedHashMap<>();
        double total = 0;

        for (Map.Entry<String, List<ScoredSpec>> entry : byFactor.entrySet()) {
            List<ScoredSpec> specs = entry.getValue();

            double contribution = specs.stream()
                    .mapToDouble(ScoredSpec::contribution)
                    .average()
                    .orElse(0.0);

            factorScores.put(
                    entry.getKey(),
                    new UpgradeScore.FactorScore(contribution, specs.stream().map(ScoredSpec::spec).toList()));

            total += contribution;
        }

        double coverage = coverage(byFactor.keySet());

        // An empty comparison and a comparison showing no change both average to
        // zero, and they mean completely different things. Coverage is what
        // separates "nothing improved" from "we could not tell", and only the
        // first is an honest NO_MEANINGFUL_CHANGE.
        boolean sufficient = !factorScores.isEmpty() && coverage >= settings.minSpecCoverage();
        if (!sufficient) {
            return new UpgradeScore(0.0, false, factorScores, List.of(), coverage);
        }

        double aggregate = total / factorScores.size();

        // Net-negative candidates clamp to zero rather than going below it. A
        // regression and a non-event are both simply not upgrades, and the
        // breakdown still records which factors went backwards. The upper
        // clamp is defensive only: a mean of per-factor contributions already
        // bounded to [-1, 1] cannot exceed 1.0 except by floating-point rounding.
        double score = round(Math.max(0.0, Math.min(1.0, aggregate)));

        return new UpgradeScore(score, true, factorScores, decidingFactors(factorScores), coverage);
    }

    /**
     * How many of the scorable factors could actually be measured.
     *
     * <p>Measured against every factor with at least one spec column, not all
     * twelve - judging against all twelve would permanently fail every
     * comparison, since five of them have no measurable column in V6 at all
     * (see {@link SpecFactorCatalog}).
     */
    private double coverage(Set<String> measuredFactors) {
        List<String> expected = Factors.ALL.stream()
                .filter(f -> !SpecFactorCatalog.UNSCORED_FACTORS.contains(f))
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
     * <p>With equal weights, influence is simply the size of the contribution.
     * Negative contributors are included: a factor that went backwards is part
     * of why the verdict came out where it did.
     */
    private List<String> decidingFactors(Map<String, UpgradeScore.FactorScore> factorScores) {
        return factorScores.entrySet().stream()
                .filter(entry -> Math.abs(entry.getValue().contribution()) > 0.05)
                .sorted(Comparator.comparingDouble(
                        (Map.Entry<String, UpgradeScore.FactorScore> entry) ->
                                Math.abs(entry.getValue().contribution()))
                        .reversed())
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
