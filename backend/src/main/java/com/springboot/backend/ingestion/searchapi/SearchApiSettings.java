package com.springboot.backend.ingestion.searchapi;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("searchapi")
public record SearchApiSettings(
        @DefaultValue("") String apiKey,
        @DefaultValue("sg") String gl,
        @DefaultValue("en") String hl,
        @DefaultValue("Singapore") String location,
        @DefaultValue("1") int maxProductsPerRun) {
    public SearchApiSettings {
        // Upper bound of 3, not arbitrary: SourceContext caps this source at 10 HTTP requests per
        // run, and each uncached product costs exactly 3 (1 discover + 2 review-page fetches) -
        // floor(10/3) = 3 is the real ceiling. A higher value would risk IllegalStateException
        // mid-run (SourceContext "Source request limit exceeded") on the first invalid-token
        // rediscovery retry, not just silently reject here.
        if (maxProductsPerRun < 1 || maxProductsPerRun > 3)
            throw new IllegalArgumentException("SEARCHAPI_MAX_PRODUCTS_PER_RUN must be 1-3");
        if (!gl.matches("[a-z]{2}") || !hl.matches("[a-z-]{2,10}")
                || location.isBlank() || location.length() > 100)
            throw new IllegalArgumentException("Invalid SearchAPI localization");
    }
    @Override public String toString() { return "SearchApiSettings[credentials redacted]"; }
}
