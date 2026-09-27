package com.springboot.backend.ingestion;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HardwareZoneReviewParserTests {
    private static byte[] bytes(String html) { return html.getBytes(StandardCharsets.UTF_8); }

    @Test void extractsReviewLinksUnderPrefixInOrder() {
        String html = """
                <a href="/mobile/smartphones/iphone-review">iPhone review</a>
                <a href="/mobile/smartphones/poco-review">Poco review</a>
                <a href="/mobile/wearables/watch-review">Watch review</a>
                """;
        var links = HardwareZoneReviewParser.listings(bytes(html), "/mobile/smartphones/", path -> true);
        assertEquals(2, links.size());
        assertEquals("https://www.hardwarezone.com.sg/mobile/smartphones/iphone-review", links.get(0).url());
        assertEquals("iPhone review", links.get(0).title());
        assertEquals("Poco review", links.get(1).title());
    }

    @Test void excludesNavSubLinksAndDedupes() {
        String html = """
                <a href="/mobile/smartphones/reviews">All reviews</a>
                <a href="/mobile/smartphones/news">News</a>
                <a href="/mobile/smartphones/features">Features</a>
                <a href="/mobile/smartphones/iphone-review">iPhone review</a>
                <a href="/mobile/smartphones/iphone-review?ref=titlestacked">iPhone review again</a>
                """;
        var links = HardwareZoneReviewParser.listings(bytes(html), "/mobile/smartphones/", path -> true);
        assertEquals(1, links.size());
        assertEquals("https://www.hardwarezone.com.sg/mobile/smartphones/iphone-review", links.get(0).url());
    }

    @Test void decodesHtmlEntitiesInTitles() {
        String html = "<a href=\"/mobile/smartphones/x-review\">NVIDIA&#x27;s &amp; the &#8220;budget&#8221; problem</a>";
        var links = HardwareZoneReviewParser.listings(bytes(html), "/mobile/smartphones/", path -> true);
        assertEquals("NVIDIA's & the \"budget\" problem", links.get(0).title());
    }

    @Test void gpuKeywordFilterExcludesCpuAndRam() {
        String html = """
                <a href="/pc/components/amd-radeon-rx-9070-gre-review">Radeon RX 9070 GRE review</a>
                <a href="/pc/components/amd-ryzen-7-7700x3d-review">Ryzen 7 7700X3D review</a>
                <a href="/pc/components/review-gskill-trident-z5">Trident Z5 review</a>
                """;
        var gpuOnly = HardwareZoneReviewParser.listings(bytes(html), "/pc/components/",
                path -> path.toLowerCase().matches(".*(radeon|geforce|rtx).*"));
        assertEquals(1, gpuOnly.size());
        assertTrue(gpuOnly.get(0).url().contains("radeon"));
    }

    @Test void extractsBodyTextStoppingAtMatchingCloseDiv() {
        String html = """
                <div class="_unrelated_63au2_1"><p>Not this text</p></div>
                <div class="_body_63au2_51 _gutter_63au2_45">
                    <div><p>Real review text starts here.</p>
                    <svg><path d="M1 1"></path></svg>
                    <p>And continues in a second paragraph.</p></div>
                </div>
                <div class="_footer_63au2_1"><p>Not this either</p></div>
                """;
        var article = HardwareZoneReviewParser.parseArticle(bytes(html));
        assertTrue(article.bodyText().contains("Real review text starts here."));
        assertTrue(article.bodyText().contains("And continues in a second paragraph."));
        assertFalse(article.bodyText().contains("Not this text"));
        assertFalse(article.bodyText().contains("Not this either"));
        assertFalse(article.bodyText().contains("M1 1"), "SVG path data must not leak into extracted text");
    }

    @Test void missingBodyDivReturnsEmptyRatherThanThrowing() {
        var article = HardwareZoneReviewParser.parseArticle(bytes("<html><body>No matching container</body></html>"));
        assertEquals("", article.bodyText());
        assertNull(article.publishedAt());
    }

    @Test void extractsDatePublishedFromJsonLd() {
        String html = """
                <script type="application/ld+json">{"@graph":[{"@type":"WebPage"},
                {"@type":"Article","datePublished":"2026-09-18T07:00:00.000Z","dateModified":"2026-09-18T06:54:00.000Z"}]}</script>
                <div class="_body_63au2_51"><div><p>Body text.</p></div></div>
                """;
        var article = HardwareZoneReviewParser.parseArticle(bytes(html));
        assertEquals(Instant.parse("2026-09-18T07:00:00.000Z"), article.publishedAt());
    }

    @Test void malformedDatePublishedLeavesNullRatherThanThrowing() {
        String html = """
                <script type="application/ld+json">{"datePublished":"not-a-real-date"}</script>
                <div class="_body_63au2_51"><div><p>Body text.</p></div></div>
                """;
        var article = HardwareZoneReviewParser.parseArticle(bytes(html));
        assertNull(article.publishedAt());
        assertTrue(article.bodyText().contains("Body text."));
    }
}
