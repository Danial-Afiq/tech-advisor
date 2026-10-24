package com.springboot.backend.ingestion.searchapi;

import com.springboot.backend.ingestion.core.Payload;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/**
 * toPrice() is the pure results-JsonNode -> Payload transform, no network - same split as
 * every other source's toPayload()-style method. EbayPriceSource.ingest()/priceOne() make the
 * one real HTTP call (SearchApiClient.ebay -> SourceContext.get()) with no test seam, same
 * limitation every other real adapter in this codebase already lives with.
 */
class EbayPriceSourceTests {
    final JsonMapper json = JsonMapper.builder().build();
    final Instant now = Instant.parse("2026-09-24T00:00:00Z");

    @Test void toPriceEmitsTheCheapestAcceptedNewListing() {
        // Already sorted price_low_to_high by the real query (condition=new,
        // sort_by=price_low_to_high) - the first accepted result with a real price wins.
        var results = json.readTree("""
                [{"title":"Apple iPhone 16 Pro 256GB","price":"$899.00","extracted_price":899.00},
                 {"title":"Apple iPhone 16 Pro 256GB","price":"$999.00","extracted_price":999.00}]
                """);
        var payload = EbayPriceSource.toPrice(812, "Apple", "iPhone 16 Pro", now, results).get();
        assertEquals("searchapi-ebay-prices", payload.sourceId());
        var price = (Payload.Price) payload.body();
        assertEquals("812", price.productReference());
        assertNull(price.variantReference());
        assertEquals(0, new java.math.BigDecimal("899.00").compareTo(price.amount()));
        assertEquals("USD", price.currency());
    }

    @Test void toPriceSkipsResultsThatFailTitleMatching() {
        // Wrong model entirely - ProductMatcher.accepts() rejects it, same denylist/suffix
        // rules Google Shopping matching already uses.
        var results = json.readTree("""
                [{"title":"Apple iPhone 16 Pro Max 256GB","price":"$999.00","extracted_price":999.00}]
                """);
        assertTrue(EbayPriceSource.toPrice(812, "Apple", "iPhone 16 Pro", now, results).isEmpty());
    }

    @Test void toPriceSkipsAMatchedTitleWithNoParseablePrice() {
        var results = json.readTree("""
                [{"title":"Apple iPhone 16 Pro 256GB","price":"","extracted_price":null}]
                """);
        assertTrue(EbayPriceSource.toPrice(812, "Apple", "iPhone 16 Pro", now, results).isEmpty());
    }

    @Test void toPriceHandlesAnEmptyResultsArray() {
        assertTrue(EbayPriceSource.toPrice(812, "Apple", "iPhone 16 Pro", now, json.readTree("[]")).isEmpty());
    }

    @Test void requestLimitAndExecutionBudgetMatchTheConfiguredProductCount() {
        var source = new EbayPriceSource(null, new EbayPriceSettings(7), null);
        assertEquals(7, source.requestLimit());
        // 30s network overhead + 1s/request (SourceContext's own pacing) - generous, not tight.
        assertTrue(source.executionBudget().getSeconds() >= 7);
    }

    @Test void sourceIdAndScheduleAreStable() {
        var source = new EbayPriceSource(null, new EbayPriceSettings(10), null);
        assertEquals("searchapi-ebay-prices", source.sourceId());
        assertEquals(java.time.Duration.ofDays(1), source.scheduleInterval().orElseThrow());
        assertTrue(source.scheduleCycleComplete());
    }
}
