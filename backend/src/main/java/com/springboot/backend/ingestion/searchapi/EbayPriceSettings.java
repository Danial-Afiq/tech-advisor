package com.springboot.backend.ingestion.searchapi;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Separate from {@link SearchApiSettings}: eBay price lookups cost exactly 1 request per
 * product (no separate discover + review-page fetches the way Google Shopping review capture
 * does), so the same "max 3" ceiling that's really about Google Shopping's 3-requests-per-product
 * math doesn't apply here and would just under-use the real per-source budget
 * ({@link com.springboot.backend.ingestion.core.IngestionSource#requestLimit()}).
 */
@ConfigurationProperties("searchapi.ebay")
public record EbayPriceSettings(
        @DefaultValue("10") int maxProductsPerRun,
        // Defaults to SMARTPHONE only, matching the behaviour before GPUs were an option here -
        // enabling GPU pricing is opt-in via SEARCHAPI_EBAY_CATEGORIES. Same vocabulary/validation
        // as SearchApiSettings.categories() (ProductMatcher's SUPPORTED_CATEGORIES).
        @DefaultValue("SMARTPHONE") List<String> categories) {
    @ConstructorBinding
    public EbayPriceSettings {
        // Upper bound of 50 is a sanity ceiling, not a request-budget calculation like
        // SearchApiSettings' - EbayPriceSource.requestLimit() is simply maxProductsPerRun
        // (1 request each), so any value here is already its own real budget.
        if (maxProductsPerRun < 1 || maxProductsPerRun > 50)
            throw new IllegalArgumentException("SEARCHAPI_EBAY_MAX_PRODUCTS_PER_RUN must be 1-50");
        categories = categories == null ? List.of() : categories.stream().map(String::trim).distinct().toList();
        if (categories.isEmpty() || !SearchApiSettings.SUPPORTED_CATEGORIES.containsAll(categories))
            throw new IllegalArgumentException("SEARCHAPI_EBAY_CATEGORIES must list SMARTPHONE and/or GPU");
    }

    /** Smartphones only, the behaviour before categories were configurable. */
    public EbayPriceSettings(int maxProductsPerRun) {
        this(maxProductsPerRun, List.of("SMARTPHONE"));
    }
}
