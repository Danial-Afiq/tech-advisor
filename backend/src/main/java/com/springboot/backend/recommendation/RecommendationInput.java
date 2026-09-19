package com.springboot.backend.recommendation;

import java.util.Map;

/**
 * Everything the caller must supply to run one assessment and persist its
 * result. {@code request} carries the verdict, relevance/preference scores
 * and deciding factors that Channel A (the deterministic verdict engine,
 * AGENTS.md §7.1) is responsible for computing - that engine is a separate
 * ticket, so this service takes its output as input rather than computing it.
 *
 * {@code deterministicFactors} becomes {@code factor_analysis.deterministic}
 * (AGENTS.md §14.11) - also caller-supplied for the same reason.
 */
public record RecommendationInput(
        long userId,
        Long currentDeviceId,
        Long triggerEventId,
        AssessRequest request,
        Map<String, Object> deterministicFactors) {

    public RecommendationInput {
        deterministicFactors = deterministicFactors == null ? Map.of() : Map.copyOf(deterministicFactors);
    }
}
