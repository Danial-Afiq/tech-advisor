package com.springboot.backend.ingestion.gpu;

import com.springboot.backend.ingestion.core.Payload;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Returns a rule name rather than throwing so the sink can drop one bad card with a logged reason
 * and keep the rest of the batch. Identity rules run first because a card we cannot name is not
 * worth range-checking, and a fixed order keeps the logged reason stable across runs.
 */
@Component
public class GpuSpecValidator {
    private final GpuValidationSettings settings;
    private final Clock clock;
    private final Pattern excluded;

    public GpuSpecValidator(GpuValidationSettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
        this.excluded = Pattern.compile(settings.excludedNamePattern());
    }

    public Optional<String> violation(Payload.GpuSpec spec) {
        if (spec == null) throw new IllegalArgumentException("GPU spec is required");
        String brand = GpuNames.brand(spec.brand());
        if (brand == null) return Optional.of("UNKNOWN_BRAND");
        if (GpuNames.model(brand, spec.modelName()) == null) return Optional.of("MISSING_MODEL_NAME");
        if (excluded.matcher(spec.modelName()).find()) return Optional.of("EXCLUDED_MODEL");
        if (spec.releaseDate() == null) return Optional.of("MISSING_RELEASE_DATE");
        if (spec.baseClockMhz() == null) return Optional.of("MISSING_BASE_CLOCK");
        if (spec.vramGb() == null) return Optional.of("MISSING_VRAM");
        if (spec.tdpWatts() == null) return Optional.of("MISSING_TDP");
        int base = spec.baseClockMhz();
        if (base < settings.baseClockMinMhz() || base > settings.baseClockMaxMhz())
            return Optional.of("BASE_CLOCK_OUT_OF_RANGE");
        if (spec.boostClockMhz() != null && spec.boostClockMhz() < base) return Optional.of("BOOST_BELOW_BASE");
        if (outside(spec.vramGb(), settings.vramMinGb(), settings.vramMaxGb())) return Optional.of("VRAM_OUT_OF_RANGE");
        if (spec.tdpWatts() < settings.tdpMinWatts() || spec.tdpWatts() > settings.tdpMaxWatts())
            return Optional.of("TDP_OUT_OF_RANGE");
        if (spec.launchMsrpUsd() != null && outside(spec.launchMsrpUsd(), settings.msrpMinUsd(), settings.msrpMaxUsd()))
            return Optional.of("MSRP_OUT_OF_RANGE");
        if (spec.releaseDate().isAfter(LocalDate.now(clock))) return Optional.of("RELEASE_DATE_IN_FUTURE");
        if (spec.releaseDate().getYear() < settings.minReleaseYear()) return Optional.of("RELEASE_DATE_TOO_OLD");
        if (spec.memoryBusBits() != null && (spec.memoryBusBits() < 32 || spec.memoryBusBits() > 8192))
            return Optional.of("MEMORY_BUS_OUT_OF_RANGE");
        return Optional.empty();
    }

    private static boolean outside(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value.compareTo(min) < 0 || value.compareTo(max) > 0;
    }
}
