package com.springboot.backend.recommendation;

import java.util.List;

/**
 * Published by {@link RecommendationRepository} inside the transaction that
 * saved one evaluation of one owned device, listing only the recommendations
 * worth telling the user about: an upgrade option (verdict other than
 * {@code NO_MEANINGFUL_CHANGE}) that is new for this (user, device, candidate)
 * tuple or whose verdict changed. An unchanged re-run publishes nothing.
 *
 * <p>Listeners use {@code AFTER_COMMIT}, so a rolled-back save produces nothing
 * and a listener failure can never undo the recommendations.
 */
public record RecommendationsPersisted(long userId, Long currentDeviceId, List<NotableRecommendation> recommendations) {

    public RecommendationsPersisted {
        recommendations = List.copyOf(recommendations);
    }

    /** One saved recommendation that is new or changed. */
    public record NotableRecommendation(long recommendationId, long candidateProductId, String verdict) {}
}
