package com.springboot.backend.ingestion.searchapi;

import com.springboot.backend.ingestion.config.IngestionSettings;
import com.springboot.backend.ingestion.core.IngestionFailure;
import com.springboot.backend.ingestion.core.Payload;
import com.springboot.backend.ingestion.core.SourceContext;
import static com.springboot.backend.ingestion.core.IngestionFailure.Code.*;
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
                [{"title":"Apple iPhone 16 Pro Black Titanium","product_id":"one","product_token":"a"},
                 {"title":"Apple iPhone 16 Pro Natural Titanium","product_id":"two","product_token":"b"}]
                """);
        assertEquals(SEARCHAPI_AMBIGUOUS_MATCH, assertThrows(IngestionFailure.class,
                () -> ProductMatcher.choose("Apple", "iPhone 16 Pro", ambiguous)).code());
        assertEquals("correct", ProductMatcher.choose("Apple", "iPhone 16 Pro", shopping()).externalId());
    }

    @Test void choosePicksUpPriceAndCurrencyWhenTheListingHasThem() {
        // Real shape confirmed live (ticket 1.8): extracted_price is a bare JSON number; price
        // is the display string whose leading symbol is the only currency marker available.
        var priced = json.readTree("""
                [{"title":"Apple iPhone 16 Pro 256GB","product_id":"correct","product_token":"token",
                  "price":"$1,299.00","extracted_price":1299.0}]
                """);
        var match = ProductMatcher.choose("Apple", "iPhone 16 Pro", priced);
        assertEquals(0, new java.math.BigDecimal("1299.0").compareTo(match.price()));
        assertEquals("USD", match.currency());
    }

    @Test void chooseLeavesPriceAndCurrencyNullWhenTheListingHasNeither() {
        var match = ProductMatcher.choose("Apple", "iPhone 16 Pro", shopping());
        assertNull(match.price()); assertNull(match.currency());
    }

    @Test void storageGbMentionsInTitleFindsTheFigureEmbeddedMidSentence() {
        // Unlike MobileApiFieldExtractor.storageOptionsGb() (anchored for a bare comma list),
        // this must find a figure buried inside an ordinary title.
        assertEquals(List.of(256), ProductMatcher.storageGbMentionsInTitle("Apple iPhone 16 Pro 256GB"));
        assertEquals(List.of(), ProductMatcher.storageGbMentionsInTitle("Apple iPhone 16 Pro"));
        assertEquals(List.of(1024), ProductMatcher.storageGbMentionsInTitle("Apple iPhone 16 Pro 1TB Cosmic Orange"));
        assertEquals(List.of(256, 512), ProductMatcher.storageGbMentionsInTitle("Model A3523.a19. 256gb/512gb Silver"));
    }

    @Test void storageGbMentionsInTitleHandlesDecimalsAndLongNonMatchingNumbers() {
        // Supports fractional TB capacities, multiple titles in one line and deduplication.
        assertEquals(List.of(1536, 256), ProductMatcher.storageGbMentionsInTitle(
                "Phone 1.5 TB or 256 GB, also 1.5TB"));
        // An extremely long digit run without a unit must not cause regex backtracking.
        assertEquals(List.of(512), ProductMatcher.storageGbMentionsInTitle(
                "7".repeat(10_000) + "X 512GB"));
    }

    @Test void prefersTheLeastVariantSpecificValidIdentity() {
        var variants = json.readTree("""
                [{"title":"Apple iPhone 16 Pro Natural Titanium","product_id":"variant","product_token":"a"},
                 {"title":"Apple iPhone 16 Pro Titanium","product_id":"canonical","product_token":"b"}]
                """);
        assertEquals("canonical", ProductMatcher.choose("Apple", "iPhone 16 Pro", variants).externalId());
    }

    @Test void listsSafeCandidatesAndHonorsTheAdminSelection() {
        var variants = json.readTree("""
                [{"title":"Apple iPhone 13 Pro Max 256GB","product_id":"256","product_token":"secret-a"},
                 {"title":"Apple iPhone 13 Pro Max 512GB","product_id":"512","product_token":"secret-b"},
                 {"title":"Apple iPhone 13 Pro Max Case","product_id":"case","product_token":"secret-c"}]
                """);
        var candidates = ProductMatcher.candidates("Apple", "iPhone 13 Pro Max", variants);
        assertEquals(List.of(new ProductMatcher.Candidate("256", "Apple iPhone 13 Pro Max 256GB"),
                new ProductMatcher.Candidate("512", "Apple iPhone 13 Pro Max 512GB")), candidates);
        assertEquals("secret-b", ProductMatcher.choose("Apple", "iPhone 13 Pro Max", variants, "512").token());
        assertEquals(SEARCHAPI_NO_MATCH, assertThrows(IngestionFailure.class,
                () -> ProductMatcher.choose("Apple", "iPhone 13 Pro Max", variants, "case")).code());
    }

    @Test void catalogueMatchAcceptsFullNameOrBareModelAlone() {
        // "Brand Model" and a bare "Model" alone are both valid catalogue lookups - same OR
        // semantics the exact-string SQL this replaced had, just fuzzy instead of exact.
        assertTrue(ProductMatcher.matchesCatalogueName("Apple iPhone 16 Pro", "Apple iPhone 16 Pro"));
        assertTrue(ProductMatcher.matchesCatalogueName("iPhone 16 Pro", "iPhone 16 Pro"));
        // A catalogue name may carry extra suffix words the admin didn't type - same tolerance
        // already proven against real Google Shopping titles.
        assertTrue(ProductMatcher.matchesCatalogueName("iPhone 16 Pro", "iPhone 16 Pro 256GB"));
        assertFalse(ProductMatcher.matchesCatalogueName("iPhone 16 Pro Max", "iPhone 16 Pro"));
        assertFalse(ProductMatcher.matchesCatalogueName("iPhone 16 Pro", "Galaxy S24 Ultra"));
    }

    @Test void matchCatalogueChecksEachCandidatesFullNameAndBareModel() {
        record Row(String brand, String model) {}
        var catalogue = List.of(new Row("ManualTargetTest", "Later Phone"), new Row("ManualTargetTest", "Earlier Phone"));
        // "Brand Model" form.
        assertEquals(1, ProductMatcher.matchCatalogue("ManualTargetTest Later Phone", catalogue,
                r -> r.brand() + " " + r.model(), Row::model).size());
        // Bare model form - no brand typed at all.
        assertEquals(1, ProductMatcher.matchCatalogue("Later Phone", catalogue,
                r -> r.brand() + " " + r.model(), Row::model).size());
        assertEquals(0, ProductMatcher.matchCatalogue("Unknown Phone", catalogue,
                r -> r.brand() + " " + r.model(), Row::model).size());
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
        when(repository.products(settings)).thenReturn(List.of(product));
        when(repository.token(product, settings)).thenReturn(Optional.empty());
        when(client.shopping(any(), eq(product.name()))).thenReturn(shopping());
        when(client.reviews(any(), anyString(), anyString())).thenReturn(reviews("5 months ago"));
        var source = new SearchApiSource(settings, repository, client, new IngestionSettings(false, null, List.of()));
        assertEquals(Duration.ZERO, source.cooldown());
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

    @Test void discoveryEmitsAVariantScopedPriceWhenTheListingNamesOneUnambiguousTier() throws Exception {
        var repository = mock(SearchApiRepository.class);
        var client = mock(SearchApiClient.class);
        when(repository.products(settings)).thenReturn(List.of(product));
        when(repository.token(product, settings)).thenReturn(Optional.empty());
        when(client.shopping(any(), eq(product.name()))).thenReturn(json.readTree("""
                [{"title":"Apple iPhone 16 Pro 256GB","product_id":"correct","product_token":"token",
                  "price":"$1,299.00","extracted_price":1299.0}]
                """));
        when(client.reviews(any(), anyString(), anyString())).thenReturn(json.createArrayNode());
        when(repository.variantIdFor(product.id(), 256)).thenReturn(Optional.of(99L));
        var source = new SearchApiSource(settings, repository, client, new IngestionSettings(false, null, List.of()));
        try (var context = new SourceContext(Clock.fixed(now, ZoneOffset.UTC), () -> {})) {
            var output = new ArrayList<Payload>(); source.ingest(context, output::add);
            var price = output.stream().map(Payload::body).filter(Payload.Price.class::isInstance)
                    .map(Payload.Price.class::cast).findFirst().orElseThrow();
            assertEquals(String.valueOf(product.id()), price.productReference());
            assertEquals("99", price.variantReference());
            // compareTo, not equals: BigDecimal scale differs between the literal here and
            // whatever text form JSON's number-to-string conversion of 1299.0 produces.
            assertEquals(0, new java.math.BigDecimal("1299.00").compareTo(price.amount()));
            assertEquals("USD", price.currency());
        }
    }

    @Test void discoveryStillRecordsPriceAtTheModelLevelWhenTheTierIsAmbiguous() throws Exception {
        // No storage mentioned in the title at all - can't attribute the price to one exact
        // row, so it's recorded at product level (variantReference null) rather than guessed.
        var repository = mock(SearchApiRepository.class);
        var client = mock(SearchApiClient.class);
        when(repository.products(settings)).thenReturn(List.of(product));
        when(repository.token(product, settings)).thenReturn(Optional.empty());
        when(client.shopping(any(), eq(product.name()))).thenReturn(json.readTree("""
                [{"title":"Apple iPhone 16 Pro","product_id":"correct","product_token":"token",
                  "price":"$999.00","extracted_price":999.0}]
                """));
        when(client.reviews(any(), anyString(), anyString())).thenReturn(json.createArrayNode());
        var source = new SearchApiSource(settings, repository, client, new IngestionSettings(false, null, List.of()));
        try (var context = new SourceContext(Clock.fixed(now, ZoneOffset.UTC), () -> {})) {
            var output = new ArrayList<Payload>(); source.ingest(context, output::add);
            var price = output.stream().map(Payload::body).filter(Payload.Price.class::isInstance)
                    .map(Payload.Price.class::cast).findFirst().orElseThrow();
            assertNull(price.variantReference());
            verify(repository, never()).variantIdFor(anyLong(), anyInt());
        }
    }

    @Test void noPriceOnTheListingEmitsNoPricePayloadAtAll() throws Exception {
        var repository = mock(SearchApiRepository.class);
        var client = mock(SearchApiClient.class);
        when(repository.products(settings)).thenReturn(List.of(product));
        when(repository.token(product, settings)).thenReturn(Optional.empty());
        when(client.shopping(any(), eq(product.name()))).thenReturn(shopping());
        when(client.reviews(any(), anyString(), anyString())).thenReturn(json.createArrayNode());
        var source = new SearchApiSource(settings, repository, client, new IngestionSettings(false, null, List.of()));
        try (var context = new SourceContext(Clock.fixed(now, ZoneOffset.UTC), () -> {})) {
            var output = new ArrayList<Payload>(); source.ingest(context, output::add);
            assertTrue(output.stream().map(Payload::body).noneMatch(Payload.Price.class::isInstance));
        }
    }

    @Test void cachedTokenRunsNeverCallShoppingSoNeverEmitAFreshPrice() throws Exception {
        // Documents the deliberate tradeoff: price only refreshes on a real discover() call,
        // never on a cache hit, to stay inside the existing per-source request budget.
        var repository = mock(SearchApiRepository.class);
        var client = mock(SearchApiClient.class);
        when(repository.products(settings)).thenReturn(List.of(product));
        when(repository.token(product, settings)).thenReturn(Optional.of("cached"));
        when(client.reviews(any(), anyString(), anyString())).thenReturn(json.createArrayNode());
        var source = new SearchApiSource(settings, repository, client, new IngestionSettings(false, null, List.of()));
        try (var context = new SourceContext(Clock.fixed(now, ZoneOffset.UTC), () -> {})) {
            var output = new ArrayList<Payload>(); source.ingest(context, output::add);
            verify(client, never()).shopping(any(), any());
            assertTrue(output.stream().map(Payload::body).noneMatch(Payload.Price.class::isInstance));
        }
    }

    @Test void untargetedNoMatchRecordsAttemptAndMovesOnRatherThanFailingTheRun() throws Exception {
        // Without this, an untargeted run re-picks the same product forever once no real Google
        // listing exists for it - never advances through the rest of the catalogue.
        var repository = mock(SearchApiRepository.class);
        var client = mock(SearchApiClient.class);
        when(repository.products(settings)).thenReturn(List.of(product));
        when(repository.token(product, settings)).thenReturn(Optional.empty());
        when(client.shopping(any(), eq(product.name()))).thenReturn(json.createArrayNode());
        var source = new SearchApiSource(settings, repository, client, new IngestionSettings(false, null, List.of()));
        try (var context = new SourceContext(Clock.fixed(now, ZoneOffset.UTC), () -> {})) {
            var output = new ArrayList<Payload>();
            assertDoesNotThrow(() -> source.ingest(context, output::add));
            assertTrue(output.isEmpty());
            verify(repository).markNoMatch(product, settings, now);
            verify(repository, never()).cache(any(), any(), any(), any());
        }
    }

    @Test void targetedNoMatchStillFailsLoudlyRatherThanSilentlySkipping() throws Exception {
        // An admin explicitly asked for this exact product - a silent skip would be misleading.
        var repository = mock(SearchApiRepository.class);
        var client = mock(SearchApiClient.class);
        when(repository.eligibleProduct(product.id())).thenReturn(Optional.of(product));
        when(repository.token(product, settings)).thenReturn(Optional.empty());
        when(client.shopping(any(), eq(product.name()))).thenReturn(json.createArrayNode());
        var source = new SearchApiSource(settings, repository, client, new IngestionSettings(false, null, List.of()));
        var target = new com.springboot.backend.ingestion.run.RunLog.ProductTarget(product.id(), product.name());
        try (var context = new SourceContext(Clock.fixed(now, ZoneOffset.UTC), () -> {}, target)) {
            var failure = assertThrows(IngestionFailure.class, () -> source.ingest(context, p -> fail("no payload expected")));
            assertEquals(SEARCHAPI_NO_MATCH, failure.code());
            verify(repository, never()).markNoMatch(any(), any(), any());
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
