package com.springboot.backend.recommendation;

import com.springboot.backend.recommendation.classification.TierMapper;
import com.springboot.backend.recommendation.classification.UpgradeClassification;
import com.springboot.backend.repository.CandidateProduct;
import java.util.List;

/**
 * Every shortlisted candidate for one owned device, classified and ranked.
 *
 * @param userDeviceId the owned device that was evaluated
 * @param userId       the device's owner
 * @param ranked       classified candidates, highest upgrade score first
 * @param skipped      candidates that survived shortlisting but could not be
 *                     classified, with the reason
 */
public record CandidateEvaluation(
        Long userDeviceId,
        Long userId,
        List<Classified> ranked,
        List<Skipped> skipped) {

    public CandidateEvaluation {
        ranked = List.copyOf(ranked);
        skipped = List.copyOf(skipped);
    }

    /**
     * The candidates that pass the early preference-gate exit (§7.1), in rank
     * order: everything except {@code NO_MEANINGFUL_CHANGE}. These are the only
     * ones worth retrieval and a model call; the rest keep their verdict
     * without spending anything.
     */
    public List<Classified> worthAssessing() {
        return ranked.stream()
                .filter(c -> !TierMapper.NO_MEANINGFUL_CHANGE.equals(c.classification().verdict()))
                .toList();
    }

    /** One candidate and its Channel A judgement. */
    public record Classified(CandidateProduct candidate, UpgradeClassification classification) {}

    /** One candidate that could not be classified, and why. */
    public record Skipped(CandidateProduct candidate, String reason) {}
}
