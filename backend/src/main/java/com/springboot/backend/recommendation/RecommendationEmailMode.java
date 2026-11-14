package com.springboot.backend.recommendation;

/**
 * Controls how a saved recommendation participates in email delivery.
 * Whole-device inventory runs defer per-row delivery until the run has
 * finished; single-product writers retain per-recommendation behaviour.
 */
public enum RecommendationEmailMode {
    INDIVIDUAL,
    DEVICE_SUMMARY
}
