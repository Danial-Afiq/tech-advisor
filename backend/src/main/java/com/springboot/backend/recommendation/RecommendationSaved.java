package com.springboot.backend.recommendation;

/**
 * Published inside the recommendation transaction whenever a new
 * recommendation row is inserted.
 *
 * <p>Email processing listens asynchronously after commit. Inventory-addition
 * rows are tagged for a device summary and ignored individually; all other
 * rows retain per-recommendation delivery. This is separate from
 * {@link RecommendationsPersisted}, whose stricter new-or-changed verdict rules
 * prevent duplicate in-app notifications.
 */
public record RecommendationSaved(long recommendationId, RecommendationEmailMode emailMode) {

    public RecommendationSaved(long recommendationId) {
        this(recommendationId, RecommendationEmailMode.INDIVIDUAL);
    }
}
