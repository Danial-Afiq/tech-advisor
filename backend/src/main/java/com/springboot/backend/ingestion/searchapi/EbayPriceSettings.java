package com.springboot.backend.ingestion.searchapi;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Separate from {@link SearchApiSettings}: eBay price lookups cost exactly 1 request per
 * product (no separate discover + review-page fetches the way Google Shopping review capture
 * does), so the same "max 3" ceiling that's really about Google Shopping's 3-requests-per-product
 * math doesn't apply here and would just under-use the real per-source budget
 * ({@link com.springboot.backend.ingestion.core.IngestionSource#requestLimit()}).
 */
@ConfigurationProperties("searchapi.ebay")
public record EbayPriceSettings(@DefaultValue("10") int maxProductsPerRun) {
    public EbayPriceSettings {
        // Upper bound of 50 is a sanity ceiling, not a request-budget calculation like
        // SearchApiSettings' - EbayPriceSource.requestLimit() is simply maxProductsPerRun
        // (1 request each), so any value here is already its own real budget.
        if (maxProductsPerRun < 1 || maxProductsPerRun > 50)
            throw new IllegalArgumentException("SEARCHAPI_EBAY_MAX_PRODUCTS_PER_RUN must be 1-50");
    }
}
