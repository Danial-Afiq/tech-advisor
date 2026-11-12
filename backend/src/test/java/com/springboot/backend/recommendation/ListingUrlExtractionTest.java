package com.springboot.backend.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ListingUrlExtractionTest {

    @Test
    void extractsRawMarkdownAndPipeDelimitedUrls() {
        assertEquals(
                "https://example.com/product?sku=123",
                RecommendationRepository.listingUrl(
                        "https://example.com/product?sku=123"
                )
        );
        assertEquals(
                "https://example.com/product",
                RecommendationRepository.listingUrl(
                        "[https://example.com/product](https://example.com/product)"
                )
        );
        assertEquals(
                "https://example.com/product",
                RecommendationRepository.listingUrl(
                        "SearchAPI | https://example.com/product | additional information"
                )
        );
        assertEquals(
                "http://example.com/listing",
                RecommendationRepository.listingUrl(
                        "Source http://example.com/listing trailing text"
                )
        );
    }

    @Test
    void rejectsIdentifiersUnsupportedSchemesAndMalformedUrls() {
        assertNull(RecommendationRepository.listingUrl(null));
        assertNull(RecommendationRepository.listingUrl("searchapi-ebay-prices"));
        assertNull(RecommendationRepository.listingUrl("ftp://example.com/product"));
        assertNull(RecommendationRepository.listingUrl("https:// bad.example/product"));
        assertNull(RecommendationRepository.listingUrl("https:///missing-host"));
    }

    @Test
    void stripsSentenceAndMarkdownDelimiters() {
        assertEquals(
                "https://example.com/product",
                RecommendationRepository.listingUrl(
                        "Listing: https://example.com/product)."
                )
        );
    }
}
