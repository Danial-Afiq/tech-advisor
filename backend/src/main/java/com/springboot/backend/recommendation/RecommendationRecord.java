package com.springboot.backend.recommendation;

import java.util.Map;

/**
 * One row for {@code recommendations}. {@code currentDeviceId} and
 * {@code triggerEventId} are nullable because the current assessment caller may
 * not yet have an upstream device or triggering-event identifier to supply.
 */
public record RecommendationRecord(
        long userId,
        Long currentDeviceId,
        long candidateProductId,
        Long triggerEventId,
        String verdict,
        String confidence,
        Map<String, Object> inputSnapshot,
        Map<String, Object> factorAnalysis,
        String reasoning,
        String aiModel,
        String promptVersion) {}
