package com.springboot.backend.recommendation.classification;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The aggregate score plus everything needed to explain it.
 *
 * <p>The breakdown is kept alongside the total on purpose: AGENTS.md §14.12
 * says never to collapse the analysis into one opaque number, and a bare 72
 * tells a reviewer nothing about which factors earned it.
 *
 * @param score            0-1 aggregate, rounded to the configured precision;
 *                         1.0 is a strong upgrade recommendation, 0.0 is not
 *                         recommended
 * @param sufficientData   false when too few specs were comparable to judge
 * @param factorScores     per-factor contribution, for the breakdown
 * @param decidingFactors  the factors that moved the score most, highest first
 * @param coverage         fraction of scorable factors that were measurable
 */
public record UpgradeScore(
        double score,
        boolean sufficientData,
        Map<String, FactorScore> factorScores,
        List<String> decidingFactors,
        double coverage) {

    public UpgradeScore {
        factorScores = Collections.unmodifiableMap(new LinkedHashMap<>(factorScores));
        decidingFactors = List.copyOf(decidingFactors);
    }

    /**
     * One factor's contribution.
     *
     * @param priority effective priority after normalization/fallback
     * @param weight priority divided by the sum of measured-factor priorities
     * @param improvement normalised signed strength in {@code [-1, 1]}
     * @param contribution improvement multiplied by weight
     * @param specs       the specs that fed this factor
     */
    public record FactorScore(int priority, double weight, double improvement, double contribution, List<String> specs) {
        public FactorScore {
            specs = List.copyOf(specs);
        }

        /** Positive, negative or flat - the shape §14.12 stores per factor. */
        public String impact() {
            if (improvement >= 0.5) return "HIGH_POSITIVE";
            if (improvement > 0.05) return "POSITIVE";
            if (improvement < -0.5) return "HIGH_NEGATIVE";
            if (improvement < -0.05) return "NEGATIVE";
            return "NEUTRAL";
        }
    }

    /** Exact effective priorities used, including any equal-weight fallback. */
    public Map<String, Integer> prioritiesUsed() {
        Map<String, Integer> priorities = new LinkedHashMap<>();
        factorScores.forEach((factor, detail) -> priorities.put(factor, detail.priority()));
        return Collections.unmodifiableMap(priorities);
    }
}
