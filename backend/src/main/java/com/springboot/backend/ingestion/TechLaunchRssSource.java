package com.springboot.backend.ingestion;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Ticket 1.4 — tech launch/pricing RSS ingestor.
 *
 * NOT added to ingestion.enabled-sources yet: no production sink exists for
 * Article payloads either (same situation as 1.2's MobileApiSmartphoneSource
 * — see docs/ingestion.md, "No extra domain tables or product schema are
 * created" by 1.1). Fetch + translate only until a real sink lands.
 *
 * Feed selection is NOT locked in as final (AGENTS.md §27.1 lists
 * "launch/change feeds" as an unresolved decision) — kept fully
 * config-driven rather than hardcoded, defaulting to two feeds verified
 * during research: Engadget and HardwareZone Singapore, both plain RSS 2.0,
 * both confirmed clear of AI-scraping blocks in robots.txt (unlike The Verge,
 * which explicitly disallows ClaudeBot and was dropped for that reason).
 *
 * Budget: 1 request per configured feed URL (2 by default) — well under the
 * shared 10-request/run ceiling enforced by SourceContext.
 *
 * productReference is always null on the emitted Article: matching an
 * article to a specific product is explicitly a downstream concern per
 * docs/ingestion.md, not this adapter's job.
 */
@Component
public class TechLaunchRssSource implements IngestionSource {
    private static final String DEFAULT_FEEDS =
            "https://www.engadget.com/rss.xml,https://www.hardwarezone.com.sg/_plat/api/rss/news.xml";

    private final List<String> feedUrls;

    public TechLaunchRssSource(
            @Value("${sources.tech-launch-rss.feed-urls:" + DEFAULT_FEEDS + "}") String feedUrlsCsv) {
        this.feedUrls = feedUrlsCsv.isBlank() ? List.of() : List.of(feedUrlsCsv.split("\\s*,\\s*"));
    }

    @Override
    public String sourceId() {
        return "tech-launch-rss";
    }

    @Override
    public void ingest(SourceContext context, Consumer<Payload> output) throws Exception {
        for (String feedUrl : feedUrls) {
            context.check();
            byte[] xml = context.get(URI.create(feedUrl));

            for (RssFeedParser.Entry entry : RssFeedParser.parse(xml)) {
                URI link = safeUri(entry.link());
                if (link == null) continue; // no usable URL/identity — skip rather than emit an unconstructable payload

                String externalId = entry.guid() != null ? entry.guid() : entry.link();
                Instant publishedAt = entry.publishedAt(); // may be null; Payload.validate() does not require it

                output.accept(new Payload(sourceId(), externalId, context.now(),
                        new Payload.Article(null, entry.title(), link, publishedAt, entry.description())));
            }
        }
    }

    private static URI safeUri(String link) {
        if (link == null) return null;
        try {
            URI uri = URI.create(link);
            return uri.isAbsolute() ? uri : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
