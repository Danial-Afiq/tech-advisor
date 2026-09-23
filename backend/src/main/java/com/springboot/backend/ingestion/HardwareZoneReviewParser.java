package com.springboot.backend.ingestion;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure parsing for HardwareZone review pages — no network, no Spring. Two
 * jobs: (1) find review links on a category "/reviews" listing page, (2)
 * extract a single review's body text and publish date from its article page.
 *
 * No HTML library (no JSoup, no Jackson) — this branch had neither as a
 * dependency, so this stays regex/hand-rolled like RssFeedParser's XML
 * approach, rather than adding a new dependency for one adapter.
 */
public final class HardwareZoneReviewParser {
    private HardwareZoneReviewParser() {}

    private static final String BASE = "https://www.hardwarezone.com.sg";
    private static final Pattern LINK =
            Pattern.compile("<a[^>]+href=\"(/[^\"?#]+)[^\"]*\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Set<String> NAV_SUFFIXES = Set.of("reviews", "news", "features");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    /** Decodes the handful of entities actually observed in HWZ markup (named and numeric
     * apostrophe/quote forms) - not a general HTML-entity decoder, just what's been seen in practice. */
    private static String decodeEntities(String text) {
        return text.replace("&#x27;", "'").replace("&#39;", "'")
                .replace("&#8217;", "'").replace("&#8216;", "'")
                .replace("&#8220;", "\"").replace("&#8221;", "\"")
                .replace("&amp;", "&").replace("&nbsp;", " ");
    }

    public record Listing(String url, String title) {}

    /**
     * Review links under categoryPrefix (e.g. "/mobile/smartphones/"), in
     * listing-page order, deduped, nav sub-links (reviews/news/features)
     * excluded, an optional extra filter applied (used for GPU keyword
     * filtering within the mixed "/pc/components/" category).
     */
    public static List<Listing> listings(byte[] html, String categoryPrefix, Predicate<String> filter) {
        String text = new String(html, StandardCharsets.UTF_8);
        Matcher m = LINK.matcher(text);
        LinkedHashMap<String, String> seen = new LinkedHashMap<>();
        while (m.find()) {
            String path = m.group(1);
            if (!path.startsWith(categoryPrefix)) continue;
            String slug = path.substring(categoryPrefix.length());
            if (slug.isEmpty() || NAV_SUFFIXES.contains(slug) || !filter.test(path)) continue;
            String title = decodeEntities(TAG.matcher(m.group(2)).replaceAll(" ")).replaceAll("\\s+", " ").trim();
            if (title.isEmpty()) continue;
            seen.putIfAbsent(path, title);
        }
        return seen.entrySet().stream().map(e -> new Listing(BASE + e.getKey(), e.getValue())).toList();
    }

    public record Article(Instant publishedAt, String bodyText) {}

    private static final Pattern DATE_PUBLISHED = Pattern.compile("\"datePublished\"\\s*:\\s*\"([^\"]+)\"");
    // The site's build-tied CSS-Modules class name for the review body container. The hash
    // segment (currently "63au2") changes on their next frontend redeploy - matched as a
    // pattern, not a literal, so a rebuild alone doesn't break this; a structural change would.
    private static final Pattern BODY_DIV = Pattern.compile("class=\"_body_[a-z0-9]+_51[^\"]*\"");
    private static final Pattern DIV_BOUNDARY = Pattern.compile("<div\\b|</div\\s*>");

    public static Article parseArticle(byte[] html) {
        String text = new String(html, StandardCharsets.UTF_8);
        Instant publishedAt = null;
        Matcher dm = DATE_PUBLISHED.matcher(text);
        if (dm.find()) {
            try { publishedAt = Instant.parse(dm.group(1)); } catch (RuntimeException ignored) { /* leave null */ }
        }
        return new Article(publishedAt, extractBody(text));
    }

    /**
     * Finds the body container and walks forward tracking <div>/</div> depth
     * to locate its exact matching close tag (only div nesting is tracked -
     * other tag types don't affect the boundary), then strips decorative
     * blocks and remaining markup from that fragment.
     *
     * Known limitation, not fixed here: a table-of-contents list sits inside
     * the same container right before the real prose and isn't separated out
     * - the extracted text starts with "1. Section one 2. Section two..."
     * before the actual review begins.
     */
    private static String extractBody(String html) {
        Matcher bodyMatch = BODY_DIV.matcher(html);
        if (!bodyMatch.find()) return "";
        int start = html.lastIndexOf("<div", bodyMatch.start());
        if (start < 0) return "";

        Matcher boundary = DIV_BOUNDARY.matcher(html);
        boundary.region(start, html.length());
        int depth = 0;
        int end = -1;
        while (boundary.find()) {
            if (boundary.group().startsWith("<div")) depth++;
            else if (--depth == 0) { end = boundary.end(); break; }
        }
        if (end < 0) return "";

        String fragment = html.substring(start, end);
        fragment = fragment.replaceAll("(?is)<svg\\b.*?</svg>", " ")
                .replaceAll("(?is)<script\\b.*?</script>", " ")
                .replaceAll("(?is)<style\\b.*?</style>", " ")
                .replaceAll("(?is)<button\\b.*?</button>", " ");
        String text = decodeEntities(fragment.replaceAll("(?s)<[^>]+>", " "));
        return text.replaceAll("\\s+", " ").trim();
    }
}
