package com.springboot.backend.ingestion;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RssFeedParserTests {
    private static byte[] xml(String body) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\"><channel><title>Test</title>"
                + body + "</channel></rss>").getBytes(StandardCharsets.UTF_8);
    }

    @Test void parsesAFullEntry() throws Exception {
        var entries = RssFeedParser.parse(xml(
                "<item>"
                        + "<title>Phone launched</title>"
                        + "<link>https://example.com/phone</link>"
                        + "<guid isPermaLink=\"true\">https://example.com/phone</guid>"
                        + "<pubDate>Fri, 18 Sep 2026 08:10:27 GMT</pubDate>"
                        + "<description>A new phone was announced today.</description>"
                        + "</item>"));
        assertEquals(1, entries.size());
        var e = entries.get(0);
        assertEquals("Phone launched", e.title());
        assertEquals("https://example.com/phone", e.link());
        assertEquals("https://example.com/phone", e.guid());
        assertEquals("A new phone was announced today.", e.description());
        assertEquals(Instant.parse("2026-09-18T08:10:27Z"), e.publishedAt());
    }

    @Test void missingGuidReturnsNullForCallerFallback() throws Exception {
        var entries = RssFeedParser.parse(xml(
                "<item><title>No guid</title><link>https://example.com/x</link></item>"));
        assertNull(entries.get(0).guid());
        assertEquals("https://example.com/x", entries.get(0).link());
    }

    @Test void missingOrMalformedPubDateReturnsNullRatherThanThrowing() throws Exception {
        var entries = RssFeedParser.parse(xml(
                "<item><title>No date</title><link>https://example.com/x</link></item>"
                        + "<item><title>Bad date</title><link>https://example.com/y</link><pubDate>not a date</pubDate></item>"));
        assertNull(entries.get(0).publishedAt());
        assertNull(entries.get(1).publishedAt());
    }

    @Test void parsesMultipleItemsInOrder() throws Exception {
        var entries = RssFeedParser.parse(xml(
                "<item><title>First</title><link>https://example.com/1</link></item>"
                        + "<item><title>Second</title><link>https://example.com/2</link></item>"
                        + "<item><title>Third</title><link>https://example.com/3</link></item>"));
        assertEquals(3, entries.size());
        assertEquals("First", entries.get(0).title());
        assertEquals("Third", entries.get(2).title());
    }

    @Test void rejectsDoctypeDeclarationsOutright() {
        byte[] malicious = ("<?xml version=\"1.0\"?>"
                + "<!DOCTYPE rss [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<rss version=\"2.0\"><channel><item><title>&xxe;</title></item></channel></rss>")
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(Exception.class, () -> RssFeedParser.parse(malicious));
    }

    @Test void emptyChannelReturnsEmptyList() throws Exception {
        assertTrue(RssFeedParser.parse(xml("")).isEmpty());
    }
}
