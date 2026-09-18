package com.springboot.backend.ingestion;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Pure RSS 2.0 parsing for ticket 1.4 — no network, no Spring. XXE-hardened
 * per docs/ingestion.md ("Disable DTDs/external entities in XML parsing"):
 * DOCTYPE declarations are rejected outright rather than merely having
 * external entities suppressed, which is the stricter and simpler-to-reason
 * about of the two standard defenses.
 *
 * Deliberately RSS 2.0 only (&lt;item&gt;/&lt;guid&gt;/&lt;description&gt;),
 * not Atom (&lt;entry&gt;/&lt;id&gt;/&lt;summary&gt;) — the two feeds this
 * ticket targets (Engadget, HardwareZone SG) are both RSS 2.0. A future Atom
 * feed would need a second parser, not a branch bolted onto this one.
 */
public final class RssFeedParser {
    private RssFeedParser() {}

    public record Entry(String guid, String link, String title, String description, Instant publishedAt) {}

    public static List<Entry> parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new ByteArrayInputStream(xml));

        List<Entry> entries = new ArrayList<>();
        NodeList items = doc.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            entries.add(new Entry(
                    text(item, "guid"),
                    text(item, "link"),
                    text(item, "title"),
                    text(item, "description"),
                    parseDate(text(item, "pubDate"))));
        }
        return entries;
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return null;
        String value = nodes.item(0).getTextContent();
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** RSS 2.0's standard pubDate format (RFC 822/1123). Malformed/missing -> null, never throws. */
    private static Instant parseDate(String pubDate) {
        if (pubDate == null) return null;
        try {
            return ZonedDateTime.parse(pubDate, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
