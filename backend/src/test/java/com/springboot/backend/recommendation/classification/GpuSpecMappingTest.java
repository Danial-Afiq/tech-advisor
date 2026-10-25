package com.springboot.backend.recommendation.classification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.springboot.backend.model.Gpu;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GpuSpecMappingTest {

    private final SpecComparisonService service = new SpecComparisonService();

    private static Gpu gpu(Integer boostMhz, BigDecimal vramGb, Integer tdpWatts, String architecture) {
        Gpu gpu = mock(Gpu.class);
        when(gpu.getBoostClockMhz()).thenReturn(boostMhz);
        when(gpu.getVramGb()).thenReturn(vramGb);
        when(gpu.getTdpWatts()).thenReturn(tdpWatts);
        when(gpu.getArchitecture()).thenReturn(architecture);
        return gpu;
    }

    private SpecComparison compare(Gpu owned, Gpu candidate) {
        return service.compare(GpuSpecMapping.RULES, owned, Map.of(), candidate, List.of(), List.of(),
                null, null, "SGD");
    }

    @Test
    void gpuColumnsReachTheScore() {
        SpecComparison result = compare(
                gpu(2000, new BigDecimal("8"), 200, "Ampere"),
                gpu(2500, new BigDecimal("16"), 250, "Ada Lovelace"));

        Map<String, ScoredSpec> scored = new java.util.HashMap<>();
        result.scored().forEach(spec -> scored.put(spec.spec(), spec));

        assertEquals(Factors.PERFORMANCE, scored.get("boost_clock_mhz").factor());
        assertEquals(Factors.LONGEVITY, scored.get("vram").factor());
        // Higher TDP is a regression once direction-corrected.
        assertTrue(scored.get("tdp_watts").deltaPct() < 0);
        assertEquals("Ada Lovelace", result.specDeltas().get("architecture").candidate());
    }

    @Test
    void missingColumnsAreSkippedNotZeroed() {
        SpecComparison result = compare(gpu(2000, null, null, null), gpu(2500, null, null, null));

        assertTrue(result.skippedSpecs().containsAll(List.of("vram", "tdp_watts", "base_clock_mhz")));
        assertNull(result.specDeltas().get("vram").deltaPct());
        assertFalse(result.specDeltas().containsKey("architecture"));
    }

    @Test
    void factorsAGpuCannotHaveAreUnmeasurable() {
        SpecComparison result = compare(gpu(1, null, null, null), gpu(1, null, null, null));

        assertTrue(result.unmeasurableFactors().containsAll(List.of(Factors.BATTERY, Factors.CAMERA)));
        assertFalse(result.unmeasurableFactors().contains(Factors.PERFORMANCE));
    }

    @Test
    void registryResolvesEachCategory() {
        CategorySpecRegistry registry = new CategorySpecRegistry(
                List.of(new PhoneSpecMapping(null), new GpuSpecMapping(null)));

        assertEquals(GpuSpecMapping.RULES, registry.forCategory("gpu").rules());
        assertEquals(PhoneSpecMapping.RULES, registry.forCategory("SMARTPHONE").rules());
    }
}
