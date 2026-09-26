package com.springboot.backend.recommendation.classification;

import com.springboot.backend.recommendation.AssessRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The finished Channel A judgement for one owned device against one candidate.
 *
 * <p>Everything the rest of the system needs from the classifier, and nothing
 * the AI service produced: this is the deterministic half, computed before any
 * retrieval or model call happens.
 *
 * @param verdict        one of the four tiers
 * @param upgradeScore   the 0-1 aggregate the verdict was derived from, where
 *                       1.0 is a strong upgrade recommendation and 0.0 is not
 *                       recommended
 * @param score          the full scoring result, including the breakdown
 * @param comparison     the spec comparison the score was built from
 * @param scoringVersion the configuration version that produced this
 */
public record UpgradeClassification(
        String verdict,
        double upgradeScore,
        UpgradeScore score,
        SpecComparison comparison,
        String scoringVersion) {

    /** The {@code analysis} block for {@code POST /assess} (AGENTS.md §9). */
    public AssessRequest.Analysis toAnalysis() {
        return new AssessRequest.Analysis(verdict, upgradeScore, score.decidingFactors());
    }

    /** The {@code computed} block for {@code POST /assess} (AGENTS.md §9). */
    public AssessRequest.Computed toComputed(AssessRequest.TriggerEvent triggerEvent) {
        return new AssessRequest.Computed(
                comparison.specDeltas(), comparison.benchmarkUpliftPct(), comparison.price(), triggerEvent);
    }

    /**
     * The {@code factor_analysis.deterministic} block (AGENTS.md §14.12).
     *
     * <p>Deliberately verbose. The per-factor contributions, the specs that were
     * skipped and the factors nothing can measure are all preserved, because
     * §14.12 forbids collapsing the analysis into one opaque number and a score
     * with no breakdown cannot be audited after the weights change.
     */
    public Map<String, Object> toDeterministicFactors() {
        Map<String, Object> factors = new LinkedHashMap<>();

        Map<String, Object> perFactor = new LinkedHashMap<>();
        score.factorScores().forEach((factor, detail) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("contribution", detail.contribution());
            entry.put("impact", detail.impact());
            entry.put("specs", detail.specs());
            perFactor.put(factor, entry);
        });

        factors.put("factors", perFactor);
        // Every factor counts the same until the group settles how user
        // priorities should weight the verdict (§27.5). Stamped so a persisted
        // breakdown says how it was weighted.
        factors.put("weighting", "EQUAL");
        factors.put("upgrade_score", upgradeScore);
        factors.put("scoring_version", scoringVersion);
        factors.put("coverage", score.coverage());
        factors.put("sufficient_data", score.sufficientData());
        factors.put("skipped_specs", comparison.skippedSpecs());
        // Named explicitly so a reader can tell "no column measures this" apart
        // from "this did not change" (see SpecFactorCatalog).
        factors.put("unmeasurable_factors", SpecFactorCatalog.UNSCORED_FACTORS);
        return factors;
    }

    /** True when the score rested on too little spec data to mean anything. */
    public boolean isInsufficientSpecCoverage() {
        return !score.sufficientData();
    }

    public List<String> decidingFactors() {
        return score.decidingFactors();
    }
}
