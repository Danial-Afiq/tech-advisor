package com.springboot.backend.recommendation.classification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunable inputs to the deterministic upgrade score (AGENTS.md §7.1).
 *
 * <p>These are configuration, not product truth. §27.5 records the weights and
 * band thresholds as an <em>unresolved</em> decision: the values bound here are
 * defensible starting points that the product owner is expected to calibrate
 * against representative product pairs, and §0.2 forbids presenting them as
 * final. Nothing outside this record may hard-code a threshold.
 *
 * @param scoringVersion    stamped onto every persisted analysis. Bump it
 *                          whenever a threshold, cap or weight changes, or old
 *                          recommendations stop being explainable
 * @param watchingThreshold lowest 0-1 score that is still {@code WORTH_WATCHING}
 * @param consideringThreshold lowest 0-1 score that is {@code WORTH_CONSIDERING}
 * @param strongThreshold   lowest 0-1 score that is a {@code STRONG_UPGRADE_CANDIDATE}
 * @param defaultPriority   weight for a factor the user did not rank
 * @param minSpecCoverage   fraction of weighted factors that must be measurable
 *                          before a score means anything
 * @param scorePrecision    decimal places the 0-1 score is rounded to
 */
@ConfigurationProperties("recommendation.scoring")
public record ScoringSettings(
        String scoringVersion,
        double watchingThreshold,
        double consideringThreshold,
        double strongThreshold,
        int defaultPriority,
        double minSpecCoverage,
        int scorePrecision) {

    public ScoringSettings {
        if (scoringVersion == null || scoringVersion.isBlank()) {
            throw new IllegalArgumentException("recommendation.scoring.scoring-version must be set");
        }
        // Out-of-order bands do not fail anywhere downstream - they quietly
        // produce plausible-looking wrong tiers, which is far worse than a
        // startup failure.
        if (!(0 < watchingThreshold
                && watchingThreshold < consideringThreshold
                && consideringThreshold < strongThreshold
                && strongThreshold <= 1)) {
            throw new IllegalArgumentException(
                    "Tier thresholds must satisfy 0 < watching < considering < strong <= 1, but were "
                            + watchingThreshold + ", " + consideringThreshold + ", " + strongThreshold);
        }
        if (defaultPriority < 1 || defaultPriority > 5) {
            throw new IllegalArgumentException(
                    "recommendation.scoring.default-priority must be 1-5, matching device_preferences.priorities, but was "
                            + defaultPriority);
        }
        if (minSpecCoverage < 0 || minSpecCoverage > 1) {
            throw new IllegalArgumentException(
                    "recommendation.scoring.min-spec-coverage must be a fraction 0-1, but was " + minSpecCoverage);
        }
        if (scorePrecision < 0 || scorePrecision > 6) {
            throw new IllegalArgumentException(
                    "recommendation.scoring.score-precision must be 0-6, but was " + scorePrecision);
        }
    }
}
