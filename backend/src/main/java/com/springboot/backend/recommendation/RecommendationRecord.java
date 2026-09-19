package com.springboot.backend.recommendation;

import java.util.Map;

/**
 * One row for {@code recommendations}. {@code currentDeviceId} and
 * {@code triggerEventId} are nullable - {@code user_devices}/{@code market_events}
 * don't exist yet (AGENTS.md §18.2), so nothing upstream can supply them.
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
