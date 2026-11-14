package com.springboot.backend.recommendation;

/** Published after a complete whole-device recommendation run finishes. */
public record DeviceRecommendationEvaluationCompleted(long userDeviceId) {
}
