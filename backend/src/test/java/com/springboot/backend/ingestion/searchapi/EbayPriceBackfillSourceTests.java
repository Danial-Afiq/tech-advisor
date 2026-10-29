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
 * Same ingest()-level mocking pattern as EbayPriceSourceTests - mock the repository and
 * SearchApiClient directly, drive a real SourceContext. toPrice() itself is already covered by
 * EbayPriceSourceTests (shared, only the sourceId parameter differs); these tests focus on what
 * is actually different here: no wraparound, permanent completion, and the completed-skip no-op.
 */
class EbayPriceBackfillSourceTests {
    final JsonMapper json = JsonMapper.builder().build();
    final Instant now = Instant.parse("2026-09-24T00:00:00Z");

    private JsonNode priced(String title, String price) {
        return json.readTree("[{\"title\":\"" + title + "\",\"price\":\"" + price + "\",\"extracted_price\":"
                + price.substring(1) + "}]");
    }

    @Test void requestLimitAndExecutionBudgetMatchTheConfiguredProductCount() {
        var source = new EbayPriceBackfillSource(null, new EbayPriceBackfillSettings(250, List.of("Apple")), null);
        assertEquals(250, source.requestLimit());
        assertTrue(source.executionBudget().getSeconds() >= 250);
    }

    @Test void sourceIdAndPriorityAreStable() {
        var source = new EbayPriceBackfillSource(null, new EbayPriceBackfillSettings(10, List.of("Apple")), null);
        assertEquals("searchapi-ebay-prices-backfill", source.sourceId());
        assertEquals(30, source.priority());
        assertEquals(java.time.Duration.ZERO, source.cooldown());
        assertTrue(source.scheduleInterval().isEmpty(), "follows the shared cadence while incomplete");
    }

    @Test void progressReportsPhaseAndCountsFromTheRepository() {
        var repository = mock(EbayPriceBackfillRepository.class);
        var settings = new EbayPriceBackfillSettings(10, List.of("Apple"));
        var state = new EbayPriceBackfillState();
        state.cursor = 5;
        when(repository.state()).thenReturn(state);
        when(repository.attemptedCount(5, List.of("Apple"))).thenReturn(5L);
        when(repository.targetCount(List.of("Apple"))).thenReturn(20L);
        when(repository.observationsCount()).thenReturn(3L);
        var source = new EbayPriceBackfillSource(repository, settings, null);

        var progress = source.progress().orElseThrow();
        assertEquals("backfilling", progress.phase());
        assertEquals(5, progress.completedUnits());
        assertEquals(20, progress.totalUnits());
        assertEquals(3, progress.observationsIngested());
        assertEquals(3, progress.productsCovered());

        state.completed = true;
        assertEquals("complete", source.progress().orElseThrow().phase());
    }

    @Test void settingsRejectAnEmptyBrandListAndOutOfRangeBatchSize() {
        assertThrows(IllegalArgumentException.class, () -> new EbayPriceBackfillSettings(10, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new EbayPriceBackfillSettings(0, List.of("Apple")));
        assertThrows(IllegalArgumentException.class, () -> new EbayPriceBackfillSettings(5001, List.of("Apple")));
    }

    @Test void ingestPricesEveryProductAndAdvancesTheCursorWithoutWrapping() throws Exception {
        var repository = mock(EbayPriceBackfillRepository.class);
        var client = mock(SearchApiClient.class);
        var settings = new EbayPriceBackfillSettings(10, List.of("Apple"));
        var a = new EbayPriceRepository.Product(1, "Apple", "iPhone 16", "SMARTPHONE");
        var b = new EbayPriceRepository.Product(2, "Nvidia", "RTX 5090", "GPU");
        var state = new EbayPriceBackfillState();
        when(repository.state()).thenReturn(state);
        when(repository.nextProducts(0, 10, List.of("Apple")))
                .thenReturn(new EbayPriceBackfillRepository.Batch(List.of(a, b), true));
        when(client.ebay(any(), eq("Apple iPhone 16"))).thenReturn(priced("Apple iPhone 16", "$599.00"));
        when(client.ebay(any(), eq("Nvidia RTX 5090"))).thenReturn(priced("Nvidia RTX 5090", "$1599.00"));
        var source = new EbayPriceBackfillSource(repository, settings, client);

        var output = new ArrayList<Payload>();
        try (var context = new SourceContext(Clock.fixed(now, ZoneOffset.UTC), () -> {})) {
            source.ingest(context, output::add);
        }

        assertEquals(2, output.size());
        assertEquals("searchapi-ebay-prices-backfill", output.get(0).sourceId());
        assertEquals(2, state.cursor);
        // Exhausted means permanently done, not "wrap to the start" - completed is set, and the
        // cursor stays at the last product tried rather than resetting to 0.
        assertTrue(state.completed);
        verify(repository, times(3)).save(state); // once per product, once more marking completed
    }

    @Test void ingestLeavesCursorWhereItStoppedWhenNotYetExhausted() throws Exception {
        var repository = mock(EbayPriceBackfillRepository.class);
        var client = mock(SearchApiClient.class);
        var settings = new EbayPriceBackfillSettings(1, List.of("Apple"));
        var a = new EbayPriceRepository.Product(1, "Apple", "iPhone 16", "SMARTPHONE");
        var state = new EbayPriceBackfillState();
        when(repository.state()).thenReturn(state);
        when(repository.nextProducts(0, 1, List.of("Apple")))
                .thenReturn(new EbayPriceBackfillRepository.Batch(List.of(a), false));
        when(client.ebay(any(), eq("Apple iPhone 16"))).thenReturn(priced("Apple iPhone 16", "$599.00"));
        var source = new EbayPriceBackfillSource(repository, settings, client);

        try (var context = new SourceContext(Clock.fixed(now, ZoneOffset.UTC), () -> {})) {
            source.ingest(context, p -> {});
        }

        assertEquals(1, state.cursor);
        assertFalse(state.completed);
        verify(repository, times(1)).save(state);
    }

    @Test void ingestIsANoOpOnceAlreadyCompleted() throws Exception {
        var repository = mock(EbayPriceBackfillRepository.class);
        var client = mock(SearchApiClient.class);
        var state = new EbayPriceBackfillState();
        state.completed = true;
        when(repository.state()).thenReturn(state);
        var source = new EbayPriceBackfillSource(repository, new EbayPriceBackfillSettings(10, List.of("Apple")), client);

        try (var context = new SourceContext(Clock.fixed(now, ZoneOffset.UTC), () -> {})) {
            source.ingest(context, p -> fail("should never emit once completed"));
        }

        verifyNoInteractions(client);
        verify(repository, never()).nextProducts(anyLong(), anyInt(), any());
        verify(repository, never()).save(any());
    }

    @Test void ingestPropagatesARealListingLookupFailureInsteadOfSwallowingIt() throws Exception {
        var repository = mock(EbayPriceBackfillRepository.class);
        var client = mock(SearchApiClient.class);
        var settings = new EbayPriceBackfillSettings(10, List.of("Apple"));
        var failing = new EbayPriceRepository.Product(1, "Apple", "iPhone 16", "SMARTPHONE");
        var neverReached = new EbayPriceRepository.Product(2, "Apple", "iPhone 16 Pro", "SMARTPHONE");
        var state = new EbayPriceBackfillState();
        when(repository.state()).thenReturn(state);
        when(repository.nextProducts(0, 10, List.of("Apple")))
                .thenReturn(new EbayPriceBackfillRepository.Batch(List.of(failing, neverReached), false));
        when(client.ebay(any(), eq("Apple iPhone 16"))).thenThrow(new RuntimeException("auth failed"));
        var source = new EbayPriceBackfillSource(repository, settings, client);

        var output = new ArrayList<Payload>();
        try (var context = new SourceContext(Clock.fixed(now, ZoneOffset.UTC), () -> {})) {
            assertThrows(RuntimeException.class, () -> source.ingest(context, output::add));
        }

        assertTrue(output.isEmpty());
        verify(client, never()).ebay(any(), eq("Apple iPhone 16 Pro"));
        assertEquals(0, state.cursor);
        assertFalse(state.completed);
    }
}
