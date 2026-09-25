package com.springboot.backend.recommendation.classification;

import com.springboot.backend.recommendation.AssessRequest;
import java.util.List;
import java.util.Map;

/**
 * The finished comparison of one owned device against one candidate.
 *
 * <p>Carries both halves of the work: {@code scored} drives the upgrade score,
 * while {@code specDeltas} is the {@code computed.spec_deltas} block the AI
 * service receives. They are produced together so the number the user is shown
 * and the number the model reasons about can never disagree.
 *
 * @param scored        specs that were numerically comparable
 * @param specDeltas    every spec, numeric or textual, in wire shape
 * @param benchmarkUpliftPct direction-corrected uplift, or null when neither
 *                      product has a shared benchmark
 * @param price         price against the user's budget, or null when unpriced
 * @param skippedSpecs  specs a value was missing for, kept so the breakdown can
 *                      say what was not known rather than implying a zero
 */
public record SpecComparison(
        List<ScoredSpec> scored,
        Map<String, AssessRequest.SpecDelta> specDeltas,
        Double benchmarkUpliftPct,
        AssessRequest.Price price,
        List<String> skippedSpecs) {

    public SpecComparison {
        scored = List.copyOf(scored);
        specDeltas = Map.copyOf(specDeltas);
        skippedSpecs = List.copyOf(skippedSpecs);
    }
}
