package com.springboot.backend.marketevent;

/**
 * What kind of change a {@code market_events} row records (AGENTS.md §14.9).
 *
 * <p>The column itself is free {@code TEXT}; this enum is the closed set the
 * application writes, so a typo cannot create a new kind of event. Every type
 * fires the market-event recommendation trigger when the event names a product.
 */
public enum MarketEventType {
    PRODUCT_LAUNCH,
    PRICE_CHANGE,
    BENCHMARK_UPDATE,
    SPECIFICATION_CHANGE,
    SUPPORT_CHANGE
}
