package com.springboot.backend.ingestion.gpu;

import static org.junit.jupiter.api.Assertions.*;

import com.springboot.backend.ingestion.core.Payload.GpuSpec;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GpuSpecValidatorTest {
    private final GpuSpecValidator validator = new GpuSpecValidator(GpuValidationSettingsTest.defaults(),
            Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC));

    private static GpuSpec spec(String brand, String model, LocalDate released, Integer base, Integer boost,
                                String vram, String memType, Integer bus, Integer tdp, String msrp, String arch, URI url) {
        return new GpuSpec(brand, model, released, base, boost, vram == null ? null : new BigDecimal(vram),
                memType, bus, tdp, msrp == null ? null : new BigDecimal(msrp), arch, url);
    }

    private static GpuSpec rtx4070Super() {
        return spec("NVIDIA", "GeForce RTX 4070 Super", LocalDate.of(2024, 1, 17), 1980, 2470, "12", "GDDR6X",
                192, 220, "599", "Ada Lovelace", URI.create("https://example.com/rtx-4070-super"));
    }

    private Optional<String> check(GpuSpec s) { return validator.violation(s); }

    @Test void validCardPasses() {
        assertEquals(Optional.empty(), check(rtx4070Super()));
    }

    @Test void nullOptionalFieldsPass() {
        assertEquals(Optional.empty(), check(spec("NVIDIA", "GeForce RTX 4070 Super", LocalDate.of(2024, 1, 17),
                1980, null, "12", null, null, 220, null, null, null)));
    }

    @Test void nullSpecThrows() {
        assertThrows(IllegalArgumentException.class, () -> validator.violation(null));
    }

    @Test void unknownBrand() {
        GpuSpec s = rtx4070Super();
        assertEquals(Optional.of("UNKNOWN_BRAND"), check(spec("ASUS", s.modelName(), s.releaseDate(), 1980, 2470,
                "12", "GDDR6X", 192, 220, "599", null, null)));
    }

    @Test void unknownBrandIsReportedBeforeABadClock() {
        assertEquals(Optional.of("UNKNOWN_BRAND"), check(spec("ASUS", "GeForce RTX 4070 Super",
                LocalDate.of(2024, 1, 17), 2, 2470, "12", null, null, 220, null, null, null)));
    }

    @Test void missingModelName() {
        assertEquals(Optional.of("MISSING_MODEL_NAME"), check(spec("NVIDIA", "  ", LocalDate.of(2024, 1, 17),
                1980, 2470, "12", null, null, 220, null, null, null)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GeForce RTX 4090 Laptop GPU", "RTX A6000", "GeForce RTX 3080 Max-Q",
            "RTX 6000 Ada Generation", "Quadro RTX 8000"})
    void excludedModels(String model) {
        assertEquals(Optional.of("EXCLUDED_MODEL"), check(spec("NVIDIA", model, LocalDate.of(2023, 1, 1),
                1980, 2470, "12", null, null, 220, null, null, null)));
    }

    @Test void aReleaseDateIsRequired() {
        assertEquals(Optional.of("MISSING_RELEASE_DATE"), check(spec("NVIDIA", "GeForce RTX 4070", null, 1980, null, "12", null, null, 220, null, null, null)));
    }

    @Test void clocksVramAndTdpAreOptional() {
        LocalDate d = LocalDate.of(2024, 1, 17);
        assertEquals(Optional.empty(), check(spec("NVIDIA", "GeForce RTX 4070", d, null, null, "12", null, null, 220, null, null, null)));
        assertEquals(Optional.empty(), check(spec("NVIDIA", "GeForce RTX 4070", d, 1980, null, null, null, null, 220, null, null, null)));
        assertEquals(Optional.empty(), check(spec("NVIDIA", "GeForce RTX 4070", d, 1980, null, "12", null, null, null, null, null, null)));
        assertEquals(Optional.empty(), check(spec("NVIDIA", "GeForce RTX 4070", d, null, null, null, null, null, null, null, null, null)));
    }

    @Test void aBoostClockWithNoBaseClockIsNotComparedToAnything() {
        assertEquals(Optional.empty(), check(withClocks(null, 1500)));
    }

    @Test void baseClockInGhzIsOutOfRange() {
        // 1.98 GHz mistakenly read as an MHz integer rounds to 2.
        assertEquals(Optional.of("BASE_CLOCK_OUT_OF_RANGE"), check(withClocks(2, null)));
        assertEquals(Optional.of("BASE_CLOCK_OUT_OF_RANGE"), check(withClocks(9, null)));
        assertEquals(Optional.of("BASE_CLOCK_OUT_OF_RANGE"), check(withClocks(4001, null)));
        assertEquals(Optional.empty(), check(withClocks(10, null)));
        assertEquals(Optional.empty(), check(withClocks(4000, null)));
    }

    @Test void boostBelowBase() {
        assertEquals(Optional.of("BOOST_BELOW_BASE"), check(withClocks(1980, 1500)));
        assertEquals(Optional.empty(), check(withClocks(1980, 1980)));
    }

    @Test void vramOutOfRange() {
        assertEquals(Optional.of("VRAM_OUT_OF_RANGE"), check(withVramTdpMsrp("0.000001", 220, null)));
        assertEquals(Optional.of("VRAM_OUT_OF_RANGE"), check(withVramTdpMsrp("128", 220, null)));
        assertEquals(Optional.empty(), check(withVramTdpMsrp("0.00001", 220, null)));
        assertEquals(Optional.empty(), check(withVramTdpMsrp("0.25", 220, null)));
        assertEquals(Optional.empty(), check(withVramTdpMsrp("96", 220, null)));
    }

    @Test void tdpOutOfRange() {
        assertEquals(Optional.of("TDP_OUT_OF_RANGE"), check(withVramTdpMsrp("12", 0, null)));
        assertEquals(Optional.of("TDP_OUT_OF_RANGE"), check(withVramTdpMsrp("12", 1200, null)));
    }

    @Test void msrpOutOfRange() {
        assertEquals(Optional.of("MSRP_OUT_OF_RANGE"), check(withVramTdpMsrp("12", 220, "20")));
        assertEquals(Optional.of("MSRP_OUT_OF_RANGE"), check(withVramTdpMsrp("12", 220, "5000.01")));
    }

    @Test void releaseDateInFuture() {
        assertEquals(Optional.of("RELEASE_DATE_IN_FUTURE"), check(withDate(LocalDate.of(2026, 10, 24))));
        assertEquals(Optional.empty(), check(withDate(LocalDate.of(2026, 10, 23))));
    }

    @Test void releaseDateTooOld() {
        assertEquals(Optional.of("RELEASE_DATE_TOO_OLD"), check(withDate(LocalDate.of(1979, 12, 31))));
        assertEquals(Optional.empty(), check(withDate(LocalDate.of(1980, 1, 1))));
        assertEquals(Optional.empty(), check(withDate(LocalDate.of(2009, 12, 31))));
    }

    @Test void memoryBusOutOfRange() {
        assertEquals(Optional.of("MEMORY_BUS_OUT_OF_RANGE"), check(withBus(16)));
        assertEquals(Optional.of("MEMORY_BUS_OUT_OF_RANGE"), check(withBus(8193)));
        assertEquals(Optional.empty(), check(withBus(32)));
        assertEquals(Optional.empty(), check(withBus(8192)));
    }

    private static GpuSpec withClocks(Integer base, Integer boost) {
        return spec("NVIDIA", "GeForce RTX 4070 Super", LocalDate.of(2024, 1, 17), base, boost, "12", null, null, 220, null, null, null);
    }

    private static GpuSpec withVramTdpMsrp(String vram, Integer tdp, String msrp) {
        return spec("NVIDIA", "GeForce RTX 4070 Super", LocalDate.of(2024, 1, 17), 1980, 2470, vram, null, null, tdp, msrp, null, null);
    }

    private static GpuSpec withDate(LocalDate date) {
        return spec("NVIDIA", "GeForce RTX 4070 Super", date, 1980, 2470, "12", null, null, 220, null, null, null);
    }

    private static GpuSpec withBus(Integer bus) {
        return spec("NVIDIA", "GeForce RTX 4070 Super", LocalDate.of(2024, 1, 17), 1980, 2470, "12", null, bus, 220, null, null, null);
    }
}
