package com.springboot.backend.ingestion.gpu;

import java.math.BigDecimal;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Deliberately loose: these ranges catch parse errors such as GHz read as MHz, not judge the card.
 * The excluded pattern keeps laptop and workstation parts out of a desktop-only reference catalogue.
 */
@ConfigurationProperties("gpu.validation")
public record GpuValidationSettings(
        @DefaultValue("100") int baseClockMinMhz,
        @DefaultValue("4000") int baseClockMaxMhz,
        @DefaultValue("0.5") BigDecimal vramMinGb,
        @DefaultValue("96") BigDecimal vramMaxGb,
        @DefaultValue("10") int tdpMinWatts,
        @DefaultValue("1000") int tdpMaxWatts,
        @DefaultValue("50") BigDecimal msrpMinUsd,
        @DefaultValue("5000") BigDecimal msrpMaxUsd,
        @DefaultValue("2010") int minReleaseYear,
        @DefaultValue("(?i)\\b(laptop|mobile|max-q|notebook|quadro|tesla|instinct|radeon pro|firepro|rtx a\\d+|ada generation)\\b")
        String excludedNamePattern) {
    public GpuValidationSettings {
        if (baseClockMinMhz >= baseClockMaxMhz)
            throw new IllegalArgumentException("gpu.validation base clock min must be < max");
        if (vramMinGb == null || vramMaxGb == null || vramMinGb.compareTo(vramMaxGb) >= 0)
            throw new IllegalArgumentException("gpu.validation VRAM min must be < max");
        if (tdpMinWatts >= tdpMaxWatts)
            throw new IllegalArgumentException("gpu.validation TDP min must be < max");
        if (msrpMinUsd == null || msrpMaxUsd == null || msrpMinUsd.compareTo(msrpMaxUsd) >= 0)
            throw new IllegalArgumentException("gpu.validation MSRP min must be < max");
        if (minReleaseYear < 1990 || minReleaseYear > 2100)
            throw new IllegalArgumentException("gpu.validation.min-release-year must be 1990-2100");
        if (excludedNamePattern == null)
            throw new IllegalArgumentException("gpu.validation.excluded-name-pattern is required");
        try { Pattern.compile(excludedNamePattern); }
        catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("gpu.validation.excluded-name-pattern does not compile", e);
        }
    }
}
