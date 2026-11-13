package com.springboot.backend.recommendation;

/** Raised when an AI response belongs to a device that is no longer evaluable. */
public class RecommendationEvaluationCancelledException extends RuntimeException {

    public RecommendationEvaluationCancelledException(Long userDeviceId) {
        super("Owned device " + userDeviceId + " is no longer evaluable");
    }
}
