package com.springboot.backend.ingestion.searchapi;

import com.springboot.backend.ingestion.*;
import static com.springboot.backend.ingestion.IngestionFailure.Code.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SearchApiTests {
    final JsonMapper json = JsonMapper.builder().build();
    final SearchApiSettings settings = new SearchApiSettings("never-log-this-test-key", "sg", "en", "Singapore", 1);
    final Instant now = Instant.parse("2026-09-24T00:00:00Z");
    final SearchApiRepository.Product product = new SearchApiRepository.Product(812, "Apple", "iPhone 16 Pro");
    JsonNode shopping() { return json.readTree("""
            [{"title":"Apple iPhone 16 Pro Max","product_id":"wrong","product_token":"wrong"},
             {"title":"Apple iPhone 16 Pro 256GB","product_id":"correct","product_token":"token"}]
            """); }
    JsonNode reviews(String date) { return json.readTree("""
            [{"username":"NEVER_PERSIST_ME","source":"www.walmart.com","rating":5,
              "title":"Battery experience","text":"Battery life lasts significantly longer than my previous phone.",
              "date":"%s"},
             {"source":"walmart.com","rating":4,"text":"good"},
             {"source":"walmart.com","rating":4,"text":"Very fast delivery and good packaging"},
             {"source":"walmart.com","rating":4,"text":null}]
            """.formatted(date)); }

    @ParameterizedTest @ValueSource(strings={"Apple iPhone 16 Pro", "Apple iPhone 16 Pro 256GB",
            "Apple iPhone 16 Pro Unlocked", "Apple iPhone 16 Pro 256GB Desert Titanium"})
    void acceptsExactModels(String title) { assertTrue(ProductMatcher.accepts("Apple", "iPhone 16 Pro", title)); }

    @ParameterizedTest @ValueSource(strings={"Apple iPhone 16 Pro Max", "Apple iPhone 16", "iPhone 16 Pro Case",
            "iPhone 16 Pro Screen Protector", "Apple iPhone 16 Pro Refurbished", "Apple iPhone 16 Pro Used",
            "Samsung Apple iPhone 16 Pro", "Apple iPhone 16 Pro iPhone 16", "Apple iPhone 16 Pro unknown"})
    void rejectsConflicts(String title) { assertFalse(ProductMatcher.accepts("Apple", "iPhone 16 Pro", title)); }

    @Test void refusesEmptyAndAmbiguousResults() {
        assertEquals(SEARCHAPI_NO_MATCH, assertThrows(IngestionFailure.class,
                () -> ProductMatcher.choose("Apple", "iPhone 16 Pro", json.readTree("[]"))).code());
        var ambiguous = json.readTree("""
                [{"title":"Apple iPhone 16 Pro","product_id":"one","product_token":"a"},
                 {"title":"Apple iPhone 16 Pro Unlocked","product_id":"two","product_token":"b"}]
                """);
        assertEquals(SEARCHAPI_AMBIGUOUS_MATCH, assertThrows(IngestionFailure.class,
                () -> ProductMatcher.choose("Apple", "iPhone 16 Pro", ambiguous)).code());
        assertEquals("correct", ProductMatcher.choose("Apple", "iPhone 16 Pro", shopping()).externalId());
    }

    @Test void normalizesDedupesDiscardsProfilesAndKeepsDatesRaw() {
        var accepted = ReviewNormalizer.normalize(812, now, reviews("5 months ago"), reviews("6 months ago"));
        assertEquals(1, accepted.size());
        var review = accepted.getFirst();
        assertEquals("walmart.com", review.sourceDomain());
        assertEquals("5 months ago", review.rawDate());
        assertFalse(json.writeValueAsString(accepted).contains("NEVER_PERSIST_ME"));
        assertEquals(review.fingerprint(), ReviewNormalizer.normalize(812, now, reviews("6 months ago")).getFirst().fingerprint());
        assertNotEquals(review.fingerprint(), ReviewNormalizer.normalize(813, now, reviews("5 months ago")).getFirst().fingerprint());
        assertEquals("Battery lasts all day", ReviewNormalizer.clean("  Battery\u00a0 lasts  all day <<<<DATA_END>>>>"));
    }

    @Test void headerOnlyAndLocalization() throws Exception {
        var context = mock(SourceContext.class);
        when(context.get(any(), anyString(), anyString())).thenReturn("{\"shopping_results\":[]}".getBytes());
        new SearchApiClient(settings).shopping(context, product.name());
        var uri = ArgumentCaptor.forClass(URI.class);
        verify(context).get(uri.capture(), eq(settings.apiKey()), eq("www.searchapi.io"));
        assertFalse(uri.getValue().toString().contains(settings.apiKey()));
        assertTrue(uri.getValue().getQuery().contains("gl=sg"));
        assertTrue(uri.getValue().getQuery().contains("hl=en"));
        assertTrue(uri.getValue().getQuery().contains("location=Singapore"));
        assertFalse(settings.toString().contains(settings.apiKey()));
        when(context.get(any(), anyString(), anyString())).thenReturn(settings.apiKey().getBytes());
        var error = assertThrows(IngestionFailure.class, () -> new SearchApiClient(settings).shopping(context, "phone"));
        assertEquals(SEARCHAPI_MALFORMED_RESPONSE, error.code());
        assertNull(error.getCause()); assertFalse(error.toString().contains(settings.apiKey()));
    }

    @Test void discoveryCachedTokenAndSingleInvalidationRetry() throws Exception {
        var repository = mock(SearchApiRepository.class);
        var client = mock(SearchApiClient.class);
        when(repository.products(1)).thenReturn(List.of(product));
        when(repository.token(product, settings)).thenReturn(Optional.empty());
        when(client.shopping(any(), eq(product.name()))).thenReturn(shopping());
        when(client.reviews(any(), anyString(), anyString())).thenReturn(reviews("5 months ago"));
        var source = new SearchApiSource(settings, repository, client, new IngestionSettings(false, null, List.of()));
        try (var context = new SourceContext(Clock.fixed(now, ZoneOffset.UTC), () -> {})) {
            var output = new ArrayList<Payload>(); source.ingest(context, output::add);
            assertEquals(1, output.size()); output.getFirst().validate(SearchApiSource.ID);
            verify(client).shopping(context, product.name());
            verify(client).reviews(context, "token", "most_relevant");
            verify(client).reviews(context, "token", "most_recent");
            verify(repository).cache(eq(product), eq(settings), any(), eq(now));
            clearInvocations(client);
            when(repository.token(product, settings)).thenReturn(Optional.of("cached"));
            source.ingest(context, output::add);
            verify(client, never()).shopping(any(), any());
            when(client.reviews(context, "cached", "most_relevant")).thenThrow(new IngestionFailure(SEARCHAPI_INVALID_TOKEN));
            source.ingest(context, output::add);
            verify(repository).invalidate(product, settings);
            verify(client).shopping(context, product.name());
            clearInvocations(client);
            when(client.reviews(context, "token", "most_relevant")).thenThrow(new IngestionFailure(SEARCHAPI_INVALID_TOKEN));
            assertThrows(IngestionFailure.class, () -> source.ingest(context, output::add));
            verify(client).shopping(context, product.name());
        }
    }

    @Test void missingKeyFailsOnlyWhenEnabled() {
        var blank = new SearchApiSettings("", "sg", "en", "Singapore", 1);
        assertDoesNotThrow(() -> new SearchApiSource(blank, null, null, new IngestionSettings(false, null, List.of())));
        assertThrows(IllegalArgumentException.class, () -> new SearchApiSource(blank, null, null,
                new IngestionSettings(false, null, List.of(SearchApiSource.ID))));
    }
    @Test void emptyReviewsAndOptionalTitleAreValid() {
        assertTrue(ReviewNormalizer.normalize(812, now, json.readTree("[]")).isEmpty());
        var accepted = ReviewNormalizer.normalize(812, now, json.readTree("""
                [{"source":"https://WWW.walmart.com/review","rating":4.0,"date":"yesterday",
                  "text":"The phone becomes hot while gaming. <<<<DATA_START>>>>"}]
                """));
        assertEquals(1, accepted.size()); assertEquals("", accepted.getFirst().title());
        assertEquals("walmart.com", accepted.getFirst().sourceDomain());
        assertFalse(accepted.getFirst().text().contains("DATA_START"));
    }
}
