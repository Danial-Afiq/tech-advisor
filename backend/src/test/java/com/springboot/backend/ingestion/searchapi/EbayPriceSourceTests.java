package com.springboot.backend.ingestion.searchapi;

import com.springboot.backend.ingestion.core.Payload;
import com.springboot.backend.ingestion.core.SourceContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * toPrice() is the pure results-JsonNode -> Payload transform, no network - same split as
 * every other source's toPayload()-style method. ingest()/priceOne() are tested the same way
 * SearchApiSource's own ingest() is (SearchApiTests) - mock EbayPriceRepository and
 * SearchApiClient directly, drive a real SourceContext with no socket involved at all. Only
 * SearchApiClient.ebay() itself (the literal HTTP call inside SourceContext.get()) has no
 * test seam - that limitation starts one level deeper than this source's own code.
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
        // After MobileAPI's catalogue-creation priority (10) - this only prices products that
        // already exist.
        assertEquals(30, source.priority());
        assertEquals(java.time.Duration.ZERO, source.cooldown());
        assertEquals(java.time.Duration.ofDays(14), source.scheduleInterval().orElseThrow());
        assertTrue(source.scheduleCycleComplete());
    }

    @Test void settingsRejectMaxProductsPerRunOutsideOneToFifty() {
        assertThrows(IllegalArgumentException.class, () -> new EbayPriceSettings(0));
        assertThrows(IllegalArgumentException.class, () -> new EbayPriceSettings(51));
    }

    private JsonNode priced(String title, String price) {
        return json.readTree("[{\"title\":\"" + title + "\",\"price\":\"" + price + "\",\"extracted_price\":"
                + price.substring(1) + "}]");
    }

    @Test void ingestPricesEveryProductInTheBatchAndAdvancesTheCursorAfterEach() throws Exception {
        var repository = mock(EbayPriceRepository.class);
        var client = mock(SearchApiClient.class);
        var a = new EbayPriceRepository.Product(1, "Apple", "iPhone 16");
        var b = new EbayPriceRepository.Product(2, "Apple", "iPhone 16 Pro");
        var state = new EbayPriceIngestionState();
        when(repository.state()).thenReturn(state);
        when(repository.nextProducts(0, 10)).thenReturn(new EbayPriceRepository.Batch(List.of(a, b), false));
        when(client.ebay(any(), eq("Apple iPhone 16"))).thenReturn(priced("Apple iPhone 16", "$599.00"));
        when(client.ebay(any(), eq("Apple iPhone 16 Pro"))).thenReturn(priced("Apple iPhone 16 Pro", "$899.00"));
        var source = new EbayPriceSource(repository, new EbayPriceSettings(10), client);

        var output = new ArrayList<Payload>();
        try (var context = new SourceContext(Clock.fixed(now, ZoneOffset.UTC), () -> {})) {
            source.ingest(context, output::add);
        }

        assertEquals(2, output.size());
        assertEquals("1", ((Payload.Price) output.get(0).body()).productReference());
        assertEquals("2", ((Payload.Price) output.get(1).body()).productReference());
        // Cursor saved after each product, not only once at the end - a mid-run crash still
        // keeps whatever progress the run already made.
        verify(repository, times(2)).save(state);
        assertEquals(2, state.catalogueCursor);
    }

    @Test void ingestResetsTheCursorToZeroWhenTheBatchWrapped() throws Exception {
        var repository = mock(EbayPriceRepository.class);
        var client = mock(SearchApiClient.class);
        var a = new EbayPriceRepository.Product(5, "Apple", "iPhone 16");
        var state = new EbayPriceIngestionState();
        state.catalogueCursor = 99;
        when(repository.state()).thenReturn(state);
        when(repository.nextProducts(99, 10)).thenReturn(new EbayPriceRepository.Batch(List.of(a), true));
        when(client.ebay(any(), eq("Apple iPhone 16"))).thenReturn(priced("Apple iPhone 16", "$599.00"));
        var source = new EbayPriceSource(repository, new EbayPriceSettings(10), client);

        try (var context = new SourceContext(Clock.fixed(now, ZoneOffset.UTC), () -> {})) {
            source.ingest(context, p -> {});
        }

        assertEquals(0, state.catalogueCursor);
        verify(repository, times(2)).save(state); // once at product id 5, once resetting to 0
    }

    @Test void ingestSkipsAProductWhoseListingLookupFailsWithoutAbortingTheRun() throws Exception {
        var repository = mock(EbayPriceRepository.class);
        var client = mock(SearchApiClient.class);
        var failing = new EbayPriceRepository.Product(1, "Apple", "iPhone 16");
        var ok = new EbayPriceRepository.Product(2, "Apple", "iPhone 16 Pro");
        var state = new EbayPriceIngestionState();
        when(repository.state()).thenReturn(state);
        when(repository.nextProducts(0, 10)).thenReturn(new EbayPriceRepository.Batch(List.of(failing, ok), false));
        when(client.ebay(any(), eq("Apple iPhone 16"))).thenThrow(new RuntimeException("transport failed"));
        when(client.ebay(any(), eq("Apple iPhone 16 Pro"))).thenReturn(priced("Apple iPhone 16 Pro", "$899.00"));
        var source = new EbayPriceSource(repository, new EbayPriceSettings(10), client);

        var output = new ArrayList<Payload>();
        try (var context = new SourceContext(Clock.fixed(now, ZoneOffset.UTC), () -> {})) {
            source.ingest(context, output::add);
        }

        // Only the second product's price was emitted - the first's failure was logged and
        // skipped, not raised, and the cursor still advanced past it.
        assertEquals(1, output.size());
        assertEquals("2", ((Payload.Price) output.getFirst().body()).productReference());
        assertEquals(2, state.catalogueCursor);
    }
}
