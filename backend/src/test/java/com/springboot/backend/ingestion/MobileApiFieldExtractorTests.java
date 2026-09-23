package com.springboot.backend.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MobileApiFieldExtractorTests {
    private final ObjectMapper json = new ObjectMapper();

    @Test void extractsRamFromHardwareText() {
        assertEquals(new BigDecimal("8"),
                MobileApiFieldExtractor.ramGb("Snapdragon 8 Gen 3, 8GB RAM").get());
    }

    @Test void missingRamReturnsEmptyNotError() {
        assertTrue(MobileApiFieldExtractor.ramGb("Snapdragon 8 Gen 3").isEmpty());
        assertTrue(MobileApiFieldExtractor.ramGb(null).isEmpty());
    }

    @Test void extractsStorageBatteryAndCamera() {
        assertEquals(new BigDecimal("256"), MobileApiFieldExtractor.storageGb("256GB").get());
        assertEquals(new BigDecimal("5000"), MobileApiFieldExtractor.batteryMah("5000 mAh").get());
        assertEquals(new BigDecimal("48"), MobileApiFieldExtractor.cameraMp("48 MP + 12 MP + 12 MP").get());
    }

    @Test void refreshRateScansWhicheverFieldItsActuallyIn() throws Exception {
        JsonNode display = json.readTree("{\"type\": \"AMOLED, 120Hz, HDR10+\"}");
        assertEquals(new BigDecimal("120"), MobileApiFieldExtractor.refreshRateHz(display).get());
    }

    @Test void refreshRateMissingReturnsEmpty() throws Exception {
        JsonNode display = json.readTree("{\"type\": \"AMOLED\"}");
        assertTrue(MobileApiFieldExtractor.refreshRateHz(display).isEmpty());
    }

    @Test void priceExtractsUsdPreferentially() throws Exception {
        JsonNode misc = json.readTree("{\"price\": \"$999 / \\u20ac899\"}");
        var price = MobileApiFieldExtractor.price(misc).get();
        assertEquals(new BigDecimal("999"), price.amount());
        assertEquals("USD", price.currency());
    }

    @Test void priceFallsBackToEurWhenNoUsd() throws Exception {
        JsonNode misc = json.readTree("{\"price\": \"\\u20ac899\"}");
        var price = MobileApiFieldExtractor.price(misc).get();
        assertEquals(new BigDecimal("899"), price.amount());
        assertEquals("EUR", price.currency());
    }

    @Test void priceMissingReturnsEmptyRatherThanGuessing() throws Exception {
        JsonNode misc = json.readTree("{\"note\": \"Not yet released\"}");
        assertTrue(MobileApiFieldExtractor.price(misc).isEmpty());
    }
}
