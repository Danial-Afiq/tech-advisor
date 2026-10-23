package com.springboot.backend.ingestion.searchapi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.springboot.backend.ingestion.config.IngestionSettings;
import com.springboot.backend.ingestion.core.Payload;
import com.springboot.backend.ingestion.core.SourceContext;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Ticket 1.3: SearchAPI collects GPU reviews with GPU listing rules and no phone variant lookup. */
class SearchApiGpuTests {
    final JsonMapper json = JsonMapper.builder().build();
    final SearchApiSettings settings = new SearchApiSettings("never-log-this-test-key", "sg", "en", "Singapore", 1,
            List.of("SMARTPHONE", "GPU"));
    final SearchApiRepository.Product rtx4070 = new SearchApiRepository.Product(901, "NVIDIA", "GeForce RTX 4070", "GPU");

    @Test void aGpuRunUsesGpuRulesAndRecordsThePriceWithoutAPhoneVariantLookup() throws Exception {
        var repository = mock(SearchApiRepository.class);
        var client = mock(SearchApiClient.class);
        when(repository.products(settings)).thenReturn(List.of(rtx4070));
        when(repository.token(rtx4070, settings)).thenReturn(Optional.empty());
        when(client.shopping(any(), eq(rtx4070.name()))).thenReturn(json.readTree("""
                [{"title":"MSI GeForce RTX 4070 Ventus 2X 12G OC","product_id":"msi","product_token":"token",
                  "price":"$549.00","extracted_price":549.0}]
                """));
        when(client.reviews(any(), anyString(), anyString())).thenReturn(json.readTree("""
                [{"source":"newegg.com","rating":5,"title":"Quiet card",
                  "text":"Runs cool and quiet at 1440p, a big step up from my old card."}]
                """));
        var source = new SearchApiSource(settings, repository, client, new IngestionSettings(false, null, List.of()));
        try (var context = new SourceContext(Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC), () -> {})) {
            var output = new ArrayList<Payload>();
            source.ingest(context, output::add);

            var price = output.stream().map(Payload::body).filter(Payload.Price.class::isInstance)
                    .map(Payload.Price.class::cast).findFirst().orElseThrow();
            assertNull(price.variantReference(), "a GPU's 12G is VRAM, not a phone storage tier");
            assertEquals(0, new java.math.BigDecimal("549").compareTo(price.amount()));
            var reviews = output.stream().map(Payload::body).filter(Payload.ReviewBatch.class::isInstance)
                    .map(Payload.ReviewBatch.class::cast).findFirst().orElseThrow();
            assertEquals(rtx4070.id(), reviews.productId());
            assertEquals(1, reviews.reviews().size());
            verify(repository, never()).variantIdFor(anyLong(), anyInt());
        }
    }

    @Test void categoriesMustBeKnownAndNonEmpty() {
        assertThrows(IllegalArgumentException.class,
                () -> new SearchApiSettings("", "sg", "en", "Singapore", 1, List.of("LAPTOP")));
        assertThrows(IllegalArgumentException.class,
                () -> new SearchApiSettings("", "sg", "en", "Singapore", 1, List.of()));
        assertEquals(List.of("SMARTPHONE"), new SearchApiSettings("", "sg", "en", "Singapore", 1).categories());
    }
}
