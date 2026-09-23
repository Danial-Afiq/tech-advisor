package com.springboot.backend.ingestion;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Ticket 1.4 (revised) — smartphone and GPU review text from HardwareZone
 * Singapore, for the owner-evidence/sentiment pipeline (AGENTS.md §7.2),
 * not the launch/change feed the ticket originally described.
 *
 * Discovery is via category "/reviews" listing pages, not RSS: GPU reviews
 * are too infrequent to reliably appear in the site's mixed, last-20-items
 * feeds (verified during research - a real June GPU review never showed up
 * in either feed checked), while the listing pages carry a real back-catalogue.
 *
 * Budget: 2 listing fetches + up to 4 smartphone + 4 GPU article fetches =
 * 10 requests, exactly the shared SourceContext ceiling (10 total HTTP
 * attempts per run, retries included).
 *
 * Legality: robots.txt permits these paths (only /feed/, /search/,
 * /advanced-galleries/, /topics/ are disallowed); no AI-training/scraping
 * clause found in SPH Media's terms despite a real search effort - not
 * ironclad, but the same evidentiary standard already applied to the
 * shipped Engadget/HardwareZone RSS source, not a lowered bar.
 *
 * Same situation as the other two sources this session: no production sink
 * exists yet for Article payloads, so this is fetch + translate only.
 */
@Component
public class HardwareZoneReviewSource implements IngestionSource {
    private static final String SMARTPHONE_PREFIX = "/mobile/smartphones/";
    private static final String GPU_PREFIX = "/pc/components/";
    private static final int PER_CATEGORY_LIMIT = 4;
    private static final Pattern GPU_KEYWORDS =
            Pattern.compile("radeon|geforce|\\brtx\\b|\\brx-\\d|graphics-card|\\bgpu\\b", Pattern.CASE_INSENSITIVE);

    @Override
    public String sourceId() {
        return "hardwarezone-reviews";
    }

    @Override
    public void ingest(SourceContext context, Consumer<Payload> output) throws Exception {
        context.check();
        List<HardwareZoneReviewParser.Listing> phones = HardwareZoneReviewParser.listings(
                        context.get(URI.create("https://www.hardwarezone.com.sg/mobile/smartphones/reviews")),
                        SMARTPHONE_PREFIX, path -> true)
                .stream().limit(PER_CATEGORY_LIMIT).toList();

        context.check();
        List<HardwareZoneReviewParser.Listing> gpus = HardwareZoneReviewParser.listings(
                        context.get(URI.create("https://www.hardwarezone.com.sg/pc/components/reviews")),
                        GPU_PREFIX, path -> GPU_KEYWORDS.matcher(path).find())
                .stream().limit(PER_CATEGORY_LIMIT).toList();

        for (var listing : Stream.concat(phones.stream(), gpus.stream()).toList()) {
            context.check();
            emit(context, output, listing);
        }
    }

    private void emit(SourceContext context, Consumer<Payload> output,
            HardwareZoneReviewParser.Listing listing) throws Exception {
        byte[] html = context.get(URI.create(listing.url()));
        var article = HardwareZoneReviewParser.parseArticle(html);
        if (article.bodyText().isBlank()) return; // extraction failed for this page - skip, don't emit empty text

        Instant publishedAt = article.publishedAt() != null ? article.publishedAt() : context.now();
        output.accept(new Payload(sourceId(), listing.url(), context.now(),
                new Payload.Article(null, listing.title(), URI.create(listing.url()), publishedAt, article.bodyText())));
    }
}
