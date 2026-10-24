package com.springboot.backend.ingestion.gpu;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GpuNamesTest {
    @Test void brandCanonicalisesKnownVendorSpellings() {
        assertEquals("NVIDIA", GpuNames.brand(" nvidia "));
        assertEquals("NVIDIA", GpuNames.brand("Nvidia Corporation"));
        assertEquals("AMD", GpuNames.brand("amd"));
        assertEquals("AMD", GpuNames.brand("Advanced Micro Devices"));
        assertEquals("AMD", GpuNames.brand("ATI"));
        assertEquals("Intel", GpuNames.brand("INTEL"));
    }

    @Test void brandReturnsNullForUnknownOrBlank() {
        assertNull(GpuNames.brand("ASUS"));
        assertNull(GpuNames.brand(null));
        assertNull(GpuNames.brand("   "));
    }

    @Test void modelStripsDuplicatedVendorWordForEachVendor() {
        assertEquals("GeForce RTX 4070", GpuNames.model("NVIDIA", "NVIDIA  GeForce RTX 4070 "));
        assertEquals("Radeon RX 7800 XT", GpuNames.model("AMD", "AMD Radeon RX 7800 XT"));
        assertEquals("Radeon HD 5870", GpuNames.model("AMD", "ATI Radeon HD 5870"));
        assertEquals("Arc A770", GpuNames.model("Intel", "Intel Arc A770"));
    }

    @Test void modelOnlyStripsTheMatchingVendor() {
        assertEquals("NVIDIA GeForce RTX 4070", GpuNames.model("AMD", "NVIDIA GeForce RTX 4070"));
    }

    @Test void modelCollapsesWhitespaceAndRemovesTrademarkSymbols() {
        assertEquals("GeForce RTX 4070", GpuNames.model("NVIDIA", "  GeForce®   RTX\t4070  "));
        assertEquals("Radeon RX 7900 XTX", GpuNames.model("AMD", "AMD Radeon™ RX 7900 XTX"));
    }

    @Test void modelPreservesCasingOfTheRemainder() {
        assertEquals("GeForce RTX 4070 SUPER", GpuNames.model("NVIDIA", "nvidia GeForce RTX 4070 SUPER"));
        assertEquals("geforce rtx 4070", GpuNames.model("NVIDIA", "geforce rtx 4070"));
    }

    @Test void modelReturnsNullForNullOrBlank() {
        assertNull(GpuNames.model("NVIDIA", null));
        assertNull(GpuNames.model("NVIDIA", "  "));
        assertNull(GpuNames.model("NVIDIA", "™"));
    }

    @Test void keyIgnoresCaseWhitespaceAndDuplicatedVendor() {
        String key = GpuNames.key("NVIDIA", "GeForce RTX 4070");
        assertEquals("nvidia|geforce rtx 4070", key);
        assertEquals(key, GpuNames.key("nvidia", "  NVIDIA GeForce   RTX 4070"));
    }

    @Test void keyRejectsUnknownBrandOrMissingModel() {
        assertThrows(IllegalArgumentException.class, () -> GpuNames.key("ASUS", "GeForce RTX 4070"));
        assertThrows(IllegalArgumentException.class, () -> GpuNames.key("NVIDIA", " "));
    }
}
