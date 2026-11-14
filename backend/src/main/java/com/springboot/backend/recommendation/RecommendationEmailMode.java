package com.springboot.backend.recommendation;

/**
 * Controls how a saved recommendation participates in email delivery.
 * Inventory additions defer per-row delivery until the whole device run has
 * finished; other writers retain the existing per-recommendation behaviour.
 */
public enum RecommendationEmailMode {
    INDIVIDUAL,
    DEVICE_SUMMARY
}
