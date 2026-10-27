package com.springboot.backend.recommendation;

/**
 * Published inside the recommendation transaction whenever a new
 * recommendation row is inserted.
 *
 * <p>Email processing listens after commit, so it can assess both the initial
 * deterministic recommendation and any later AI-enriched replacement. This is
 * separate from {@link RecommendationsPersisted}, whose stricter new-or-changed
 * verdict rules prevent duplicate in-app notifications.
 */
public record RecommendationSaved(long recommendationId) {
}
