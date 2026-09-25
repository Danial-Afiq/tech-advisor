package com.springboot.backend.recommendation.classification;

/**
 * One specification the classifier was able to compare, with the improvement
 * cap that normalised it.
 *
 * @param spec        the {@code phone} column, or a synthetic key such as
 *                    {@code benchmark} or {@code price}
 * @param factor      which of the twelve {@link Factors} this spec speaks to
 * @param current     the owned device's value, after {@code spec_overrides}
 * @param candidate   the candidate's value
 * @param deltaPct    signed percentage change, already direction-corrected so
 *                    positive always means better
 * @param improvementCap the delta treated as a full-strength improvement
 */
public record ScoredSpec(
        String spec,
        String factor,
        double current,
        double candidate,
        double deltaPct,
        double improvementCap) {

    /**
     * The normalised, signed contribution in {@code [-1, 1]}.
     *
     * <p>Capping matters: doubling a battery is better than adding a tenth, but
     * it is not ten times better, and without a ceiling one freakish spec would
     * swamp every other factor in the aggregate.
     */
    public double contribution() {
        double raw = deltaPct / improvementCap;
        return Math.max(-1.0, Math.min(1.0, raw));
    }
}
