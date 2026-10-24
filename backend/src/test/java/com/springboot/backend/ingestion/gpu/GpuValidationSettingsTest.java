package com.springboot.backend.ingestion.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class GpuValidationSettingsTest {
    /** Binds with no properties, so every value comes from @DefaultValue - no Spring context. */
    static GpuValidationSettings defaults() {
        return new Binder(new MapConfigurationPropertySource())
                .bindOrCreate("gpu.validation", GpuValidationSettings.class);
    }

    @Test void defaultsBindToTheDocumentedValues() {
        GpuValidationSettings s = defaults();
        assertEquals(100, s.baseClockMinMhz());
        assertEquals(4000, s.baseClockMaxMhz());
        assertEquals(0, new BigDecimal("0.5").compareTo(s.vramMinGb()));
        assertEquals(0, new BigDecimal("96").compareTo(s.vramMaxGb()));
        assertEquals(10, s.tdpMinWatts());
        assertEquals(1000, s.tdpMaxWatts());
        assertEquals(0, new BigDecimal("50").compareTo(s.msrpMinUsd()));
        assertEquals(0, new BigDecimal("5000").compareTo(s.msrpMaxUsd()));
        assertEquals(2010, s.minReleaseYear());
        assertEquals("(?i)\\b(laptop|mobile|max-q|notebook|quadro|tesla|instinct|radeon pro|firepro|rtx a\\d+|ada generation)\\b",
                s.excludedNamePattern());
    }

    @Test void applicationPropertiesMatchTheAnnotationDefaults() throws Exception {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application.properties")) { props.load(in); }
        Map<String, String> gpu = new HashMap<>();
        props.stringPropertyNames().stream().filter(k -> k.startsWith("gpu.validation."))
                .forEach(k -> gpu.put(k, props.getProperty(k)));
        assertEquals(10, gpu.size());
        GpuValidationSettings bound = new Binder(new MapConfigurationPropertySource(gpu))
                .bindOrCreate("gpu.validation", GpuValidationSettings.class);
        GpuValidationSettings d = defaults();
        assertEquals(d.excludedNamePattern(), bound.excludedNamePattern());
        assertEquals(d.baseClockMinMhz(), bound.baseClockMinMhz());
        assertEquals(d.baseClockMaxMhz(), bound.baseClockMaxMhz());
        assertEquals(0, d.vramMinGb().compareTo(bound.vramMinGb()));
        assertEquals(0, d.vramMaxGb().compareTo(bound.vramMaxGb()));
        assertEquals(d.tdpMinWatts(), bound.tdpMinWatts());
        assertEquals(d.tdpMaxWatts(), bound.tdpMaxWatts());
        assertEquals(0, d.msrpMinUsd().compareTo(bound.msrpMinUsd()));
        assertEquals(0, d.msrpMaxUsd().compareTo(bound.msrpMaxUsd()));
        assertEquals(d.minReleaseYear(), bound.minReleaseYear());
    }

    @Test void minNotBelowMaxThrows() {
        assertThrows(IllegalArgumentException.class, () -> settings(4000, 4000, "0.5", "96", 10, 1000, "50", "5000", 2010, "x"));
        assertThrows(IllegalArgumentException.class, () -> settings(100, 4000, "96", "0.5", 10, 1000, "50", "5000", 2010, "x"));
        assertThrows(IllegalArgumentException.class, () -> settings(100, 4000, "0.5", "96", 1000, 10, "50", "5000", 2010, "x"));
        assertThrows(IllegalArgumentException.class, () -> settings(100, 4000, "0.5", "96", 10, 1000, "50", "50", 2010, "x"));
    }

    @Test void releaseYearOutsideSaneBoundsThrows() {
        assertThrows(IllegalArgumentException.class, () -> settings(100, 4000, "0.5", "96", 10, 1000, "50", "5000", 1989, "x"));
        assertThrows(IllegalArgumentException.class, () -> settings(100, 4000, "0.5", "96", 10, 1000, "50", "5000", 2101, "x"));
    }

    @Test void badRegexThrows() {
        assertThrows(IllegalArgumentException.class, () -> settings(100, 4000, "0.5", "96", 10, 1000, "50", "5000", 2010, "(unclosed"));
    }

    private static GpuValidationSettings settings(int clockMin, int clockMax, String vramMin, String vramMax,
                                                  int tdpMin, int tdpMax, String msrpMin, String msrpMax,
                                                  int year, String pattern) {
        return new GpuValidationSettings(clockMin, clockMax, new BigDecimal(vramMin), new BigDecimal(vramMax),
                tdpMin, tdpMax, new BigDecimal(msrpMin), new BigDecimal(msrpMax), year, pattern);
    }
}
