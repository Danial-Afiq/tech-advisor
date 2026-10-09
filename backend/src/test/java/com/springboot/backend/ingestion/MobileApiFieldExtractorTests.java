package com.springboot.backend.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
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

    // Real text pulled directly from a live GET /devices/43/ (Apple iPhone 17 Pro), ticket 1.8.
    @Test void chipsetHandlesRamLeadingTheChipsetNotOnlyTrailingIt() {
        // RAM leads here ("12GB RAM, Apple A19 Pro") - the opposite order from the Snapdragon
        // fixture below. Stripping only a RAM *prefix* (the original implementation) returned
        // empty for this real device: it never looked at what came after the match.
        assertEquals("Apple A19 Pro", MobileApiFieldExtractor.chipset("12GB RAM, Apple A19 Pro").get());
    }

    @Test void chipsetStillHandlesRamTrailingTheChipset() {
        assertEquals("Snapdragon 8 Gen 3", MobileApiFieldExtractor.chipset("Snapdragon 8 Gen 3, 8GB RAM").get());
    }

    @Test void chipsetMissingEntirelyReturnsEmpty() {
        // Real BLU G5 hardware text (mobileapi-response.json) - no chipset recorded at all.
        assertTrue(MobileApiFieldExtractor.chipset("2 GB RAM, ").isEmpty());
        assertTrue(MobileApiFieldExtractor.chipset(null).isEmpty());
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

    // Real text pulled directly from a live GET /devices/2/ (Alcatel 1B (2022)) - not invented.

    @Test void priceTextParsesRealCurrencyCodeForm() {
        var price = MobileApiFieldExtractor.priceText("About 100 EUR").get();
        assertEquals(new BigDecimal("100"), price.amount());
        assertEquals("EUR", price.currency());
    }

    @Test void priceTextStillHandlesSymbolForm() {
        var price = MobileApiFieldExtractor.priceText("$249.99").get();
        assertEquals(new BigDecimal("249.99"), price.amount());
        assertEquals("USD", price.currency());
    }

    @Test void priceTextHandlesRealThinSpaceBetweenSymbolAndAmount() {
        // Real iPhone SE (3rd Gen) misc.price - U+2009 THIN SPACE between symbol and number,
        // not a plain ASCII space. Plain \s misses this entirely (confirmed - this failed
        // before Pattern.UNICODE_CHARACTER_CLASS was added).
        var price = MobileApiFieldExtractor.priceText(
                "£ 145.00 / € 167.00 / $ 121.87 / C$ 199.74").get();
        assertEquals(new BigDecimal("121.87"), price.amount());
        assertEquals("USD", price.currency());
    }

    @Test void priceTextBlankOrNullReturnsEmpty() {
        assertTrue(MobileApiFieldExtractor.priceText("").isEmpty());
        assertTrue(MobileApiFieldExtractor.priceText(null).isEmpty());
    }

    // Real text pulled directly from a live GET /devices/43/ (Apple iPhone 17 Pro), ticket 1.8 -
    // not invented. Thin space (U+2009) between symbol and amount, same as the iPhone SE case
    // above; comma thousands separator is the new thing this string exercises.
    @Test void priceTextParsesCommaGroupedThousandsRatherThanTruncatingAtTheComma() {
        var price = MobileApiFieldExtractor.priceText(
                "€ 1,299.00 / £ 1,099.00 / ₹ 134,900").get();
        // Before the fix, the bare \d+ in PRICE_EUR stopped at the comma and this returned 1.
        assertEquals(new BigDecimal("1299.00"), price.amount());
        assertEquals("EUR", price.currency());
    }

    @Test void priceTextFallsBackToInrWhenNoOtherCurrencyMatches() {
        var price = MobileApiFieldExtractor.priceText("₹ 134,900").get();
        assertEquals(new BigDecimal("134900"), price.amount());
        assertEquals("INR", price.currency());
    }

    // Review-flagged regression: a 4+-digit amount with NO thousands comma ("$1299.00") was
    // still mis-parsed after the comma-grouped fix above - the first alternative's \d{1,3}
    // matched just "129" with zero (?:,\d{3}) repeats, which is a complete match for that
    // alternative with nothing mandatory after it, so the engine never tried the second,
    // fully-greedy alternative. Fixed by requiring at least one comma group in the first
    // alternative instead of allowing zero.
    @Test void priceTextParsesUngroupedFourAndFiveDigitAmountsInFull() {
        assertEquals(new BigDecimal("1299.00"), MobileApiFieldExtractor.priceText("$1299.00").get().amount());
        assertEquals(new BigDecimal("2499.90"), MobileApiFieldExtractor.priceText("$2499.90").get().amount());
        assertEquals(new BigDecimal("9999"), MobileApiFieldExtractor.priceText("$9999").get().amount());
        // The code-form path (PRICE_CODE) shares the same AMOUNT pattern - must not regress too.
        assertEquals(new BigDecimal("1299"), MobileApiFieldExtractor.priceText("1299 EUR").get().amount());
    }

    @Test void priceTextStillParsesCommaGroupedAmountsAfterTheUngroupedFix() {
        assertEquals(new BigDecimal("1299.00"), MobileApiFieldExtractor.priceText("$1,299.00").get().amount());
        assertEquals(new BigDecimal("1299"), MobileApiFieldExtractor.priceText("1,299 EUR").get().amount());
    }

    // Real text pulled directly from live GET /devices/43/ and /devices/42/ (iPhone 17 Pro /
    // Pro Max), ticket 1.8 - MobileAPI lists every storage tier a model ships in as one
    // free-text field, not separate records.
    @Test void storageOptionsGbParsesTheRealCommaSeparatedTierList() {
        assertEquals(java.util.List.of(256, 512, 1024),
                MobileApiFieldExtractor.storageOptionsGb("256GB, 512GB, 1TB"));
        assertEquals(java.util.List.of(256, 512, 2048),
                MobileApiFieldExtractor.storageOptionsGb("256GB, 512GB, 2TB"));
    }

    @Test void storageOptionsGbHandlesTheRealGarbageAndBlankCases() {
        // Real captured values, both from mobileapi-response.json - the first has the unit
        // glued to unrelated text with no separator, the second is simply empty.
        assertEquals(java.util.List.of(4), MobileApiFieldExtractor.storageOptionsGb("4 GBBuy memory card"));
        assertEquals(java.util.List.of(), MobileApiFieldExtractor.storageOptionsGb(""));
        assertEquals(java.util.List.of(), MobileApiFieldExtractor.storageOptionsGb(null));
    }

    @Test void storageOptionsGbDeduplicatesWithoutReordering() {
        assertEquals(java.util.List.of(128, 256),
                MobileApiFieldExtractor.storageOptionsGb("128GB, 256GB, 128GB"));
    }

    @Test void cpuGhzTakesFirstClockSpeedFromMulticoreText() {
        assertEquals(new BigDecimal("2.0"),
                MobileApiFieldExtractor.cpuGhz("Quad-core 2.0 GHz Cortex-A53").get());
        // Real big.LITTLE text has two clock speeds - first one wins, documented behaviour.
        assertEquals(new BigDecimal("1.6"), MobileApiFieldExtractor.cpuGhz(
                "Octa-core (4x1.6 GHz Cortex-A55 & 4x1.2 GHz Cortex-A55)").get());
    }

    @Test void displaySizeInchesParsesRealDisplayText() {
        assertEquals(new BigDecimal("5.5"),
                MobileApiFieldExtractor.displaySizeInches("5.5 inches, 78.1 cm2(~74.0% screen-to-body ratio)").get());
    }

    @Test void weightGramsHandlesUnitAndBareNumberForms() {
        assertEquals(new BigDecimal("172"), MobileApiFieldExtractor.weightGrams("172 g (6.07 oz)").get());
        // Top-level "weight" field sometimes carries no unit at all.
        assertEquals(new BigDecimal("187.00"), MobileApiFieldExtractor.weightGrams("187.00").get());
        assertTrue(MobileApiFieldExtractor.weightGrams(null).isEmpty());
        assertTrue(MobileApiFieldExtractor.weightGrams("").isEmpty());
    }

    @Test void releaseDateParsesQuarterYearAsFirstMonthOfQuarter() {
        assertEquals(LocalDate.of(2019, 7, 1),
                MobileApiFieldExtractor.releaseDate("3Q 2019", null).get());
    }

    @Test void releaseDateFallsBackToAnnouncedMonthInDescription() {
        var date = MobileApiFieldExtractor.releaseDate("",
                "alcatel 1B (2022) Android smartphone. Announced May 2022. Features 5.5″ display.").get();
        assertEquals(LocalDate.of(2022, 5, 1), date);
    }

    @Test void releaseDateEmptyWhenNeitherFormMatches() {
        assertTrue(MobileApiFieldExtractor.releaseDate("", "No date information here.").isEmpty());
        assertTrue(MobileApiFieldExtractor.releaseDate(null, null).isEmpty());
    }
}
