package com.springboot.backend.recommendation;

/** Published after a newly added device's complete recommendation run finishes. */
public record DeviceRecommendationEvaluationCompleted(long userDeviceId) {
}
