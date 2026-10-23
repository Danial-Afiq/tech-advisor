package com.springboot.backend.ingestion.searchapi;

import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("searchapi")
public record SearchApiSettings(
        @DefaultValue("") String apiKey,
        @DefaultValue("sg") String gl,
        @DefaultValue("en") String hl,
        @DefaultValue("Singapore") String location,
        @DefaultValue("1") int maxProductsPerRun,
        @DefaultValue("SMARTPHONE") List<String> categories) {
    /** Product categories ProductMatcher has a listing vocabulary for (ticket 1.3 added GPU). */
    public static final Set<String> SUPPORTED_CATEGORIES = Set.of("SMARTPHONE", "GPU");

    @ConstructorBinding
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
        categories = categories == null ? List.of() : categories.stream().map(String::trim).distinct().toList();
        if (categories.isEmpty() || !SUPPORTED_CATEGORIES.containsAll(categories))
            throw new IllegalArgumentException("SEARCHAPI_CATEGORIES must list SMARTPHONE and/or GPU");
    }

    /** Smartphones only, the behaviour before categories were configurable. */
    public SearchApiSettings(String apiKey, String gl, String hl, String location, int maxProductsPerRun) {
        this(apiKey, gl, hl, location, maxProductsPerRun, List.of("SMARTPHONE"));
    }

    @Override public String toString() { return "SearchApiSettings[credentials redacted]"; }
}
