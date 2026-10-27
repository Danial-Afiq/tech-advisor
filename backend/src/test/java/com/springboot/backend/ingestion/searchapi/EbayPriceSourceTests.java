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

    @Test void toPricePicksTheCheapestMatchEvenWhenResultsArriveOutOfPriceOrder() {
        // SearchAPI.io's own docs confirm price_low_to_high is unsupported on ebay.com (the
        // default marketplace, used here) - results can come back in best_match order instead,
        // so toPrice() must never assume the first priced match is the cheapest one.
        var results = json.readTree("""
                [{"title":"Apple iPhone 16 Pro 256GB","price":"$999.00","extracted_price":999.00},
                 {"title":"Apple iPhone 16 Pro 256GB","price":"$849.00","extracted_price":849.00},
                 {"title":"Apple iPhone 16 Pro 256GB","price":"$905.00","extracted_price":905.00}]
                """);
        var price = (Payload.Price) EbayPriceSource.toPrice(812, "Apple", "iPhone 16 Pro", now, results).get().body();
        assertEquals(0, new java.math.BigDecimal("849.00").compareTo(price.amount()));
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
        assertEquals(java.time.Duration.ofDays(1), source.scheduleInterval().orElseThrow());
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

    @Test void ingestPropagatesARealListingLookupFailureInsteadOfSwallowingIt() throws Exception {
        // A real failure (auth, malformed response, transport, rate limit - anything
        // client.ebay() can throw) must abort the run and reach the orchestrator, not be
        // silently treated as "no listing for this product". SearchApiClient.ebay() has no
        // "legitimate no match" exception code the way SearchApiSource's discovery step does -
        // an empty result set already returns cleanly without throwing - so nothing here is
        // safe to catch and skip.
        var repository = mock(EbayPriceRepository.class);
        var client = mock(SearchApiClient.class);
        var failing = new EbayPriceRepository.Product(1, "Apple", "iPhone 16");
        var neverReached = new EbayPriceRepository.Product(2, "Apple", "iPhone 16 Pro");
        var state = new EbayPriceIngestionState();
        when(repository.state()).thenReturn(state);
        when(repository.nextProducts(0, 10)).thenReturn(new EbayPriceRepository.Batch(List.of(failing, neverReached), false));
        when(client.ebay(any(), eq("Apple iPhone 16"))).thenThrow(new RuntimeException("auth failed"));
        var source = new EbayPriceSource(repository, new EbayPriceSettings(10), client);

        var output = new ArrayList<Payload>();
        try (var context = new SourceContext(Clock.fixed(now, ZoneOffset.UTC), () -> {})) {
            assertThrows(RuntimeException.class, () -> source.ingest(context, output::add));
        }

        assertTrue(output.isEmpty());
        verify(client, never()).ebay(any(), eq("Apple iPhone 16 Pro"));
        // Cursor is simply never advanced past the failing product - nothing to roll back,
        // since save() happens only after a product succeeds.
        assertEquals(0, state.catalogueCursor);
    }
}
