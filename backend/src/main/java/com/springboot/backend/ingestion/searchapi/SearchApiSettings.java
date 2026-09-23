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
        if (maxProductsPerRun < 1 || maxProductsPerRun > 2)
            throw new IllegalArgumentException("SEARCHAPI_MAX_PRODUCTS_PER_RUN must be 1 or 2");
        if (!gl.matches("[a-z]{2}") || !hl.matches("[a-z-]{2,10}")
                || location.isBlank() || location.length() > 100)
            throw new IllegalArgumentException("Invalid SearchAPI localization");
    }
    @Override public String toString() { return "SearchApiSettings[credentials redacted]"; }
}
