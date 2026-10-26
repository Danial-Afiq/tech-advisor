package com.springboot.backend.ingestion.searchapi;

import com.springboot.backend.ingestion.core.IngestionSource;
import com.springboot.backend.ingestion.core.Payload;
import com.springboot.backend.ingestion.core.SourceContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Scheduled-ingestion price capture from eBay (via SearchAPI.io's ebay_search engine,
 * the same account/API key already configured for Google Shopping - see
 * {@link SearchApiClient#ebay}). Market change detection (price-change events) needs a
 * current price per product; this is the second real source for one, alongside the existing
 * Google Shopping discover-and-cache flow in {@link SearchApiSource}.
 *
 * Deliberately NOT the same shape as SearchApiSource's targeted/untargeted/cached-token
 * flow: there is no review re-fetch to cache a token for, so every run is simply "price the
 * next {@code maxProductsPerRun} products in catalogue order" - a plain round-robin sweep via
 * {@link EbayPriceRepository}, 1 real HTTP request per product, no per-product state beyond
 * the single shared cursor. This directly answers the three things a repeatable, quota-safe
 * scheduled price feed needs:
 *   - never re-prices the same handful of products every run (the cursor advances);
 *   - never queries forever without result (one row, one price observation, done);
 *   - bounded, configurable request cost per run via {@link EbayPriceSettings}, independent
 *     of Google Shopping's own budget.
 *
 * Categories (SMARTPHONE and/or GPU, {@link EbayPriceSettings#categories}) share one combined
 * cursor rather than one each - same reasoning {@link SearchApiRepository}'s untargeted picker
 * already applies: one id-ordered rotation across every configured category, so a category
 * with more rows simply gets proportionally more turns per sweep instead of either category
 * needing its own independent pacing/quota slice.
 *
 * Default config (10 products/run, once/day - see scheduleInterval(), independent of the
 * shared scheduler cadence in RunStore.INTERVAL) costs ~300 requests a month against the
 * shared SearchAPI.io account quota - deliberately conservative given that quota is shared
 * with Google Shopping review ingestion too and has been exhausted mid-month before
 * (Smartphone DB Backfill session). Both numbers are plain config, tune freely. Running daily
 * rather than on the (far longer) shared cadence matters here specifically: a round-robin
 * sweep of N products in catalogue order only reaches every product once every
 * N / maxProductsPerRun runs, so a slow cadence directly means stale prices for most of the
 * catalogue most of the time - at the full 2-week shared cadence a ~450-device catalogue would
 * take over 600 days for one complete sweep, useless for market-change detection's purpose.
 *
 * Backfilling the whole catalogue once at the start (rather than waiting for the round-robin
 * sweep to reach every product for the first time over many days) is explicitly out of scope
 * here - a later, separate pass, same reasoning ticket 1.8's backfill was kept separate from
 * its schema work.
 */
@Component
@EnableConfigurationProperties(EbayPriceSettings.class)
public class EbayPriceSource implements IngestionSource {
    public static final String ID = "searchapi-ebay-prices";
    private static final int NETWORK_OVERHEAD_SECONDS = 30;

    private final EbayPriceRepository repository;
    private final EbayPriceSettings settings;
    private final SearchApiClient client;

    public EbayPriceSource(EbayPriceRepository repository, EbayPriceSettings settings, SearchApiClient client) {
        this.repository = repository;
        this.settings = settings;
        this.client = client;
    }

    @Override public String sourceId() { return ID; }
    // After MobileAPI's catalogue-creation priority (10) - this only prices products that
    // already exist, same ordering reasoning as SearchApiSource/TechApiBenchmarkSource.
    @Override public int priority() { return 30; }
    @Override public Duration cooldown() { return Duration.ZERO; }
    // Exactly 1 request per product - no discover-then-fetch-reviews split like Google
    // Shopping's flow, so the per-run budget is simply the product count itself.
    @Override public int requestLimit() { return settings.maxProductsPerRun(); }
    @Override public Duration executionBudget() {
        return Duration.ofSeconds(requestLimit() + NETWORK_OVERHEAD_SECONDS);
    }
    // Deliberately conservative default, independent of the shared scheduler cadence - see
    // class Javadoc for the request-budget math and why this needs to run more often than
    // RunStore.INTERVAL to keep a full catalogue sweep in a reasonable timeframe.
    @Override public Optional<Duration> scheduleInterval() { return Optional.of(Duration.ofDays(1)); }
    // Always true: unlike TechApiBenchmarkSource's one-time backfill-then-incremental shape,
    // this source has no "finished" state to race toward faster - every run prices its own
    // bounded batch and that is intentionally the whole job for that run. Returning false to
    // chase a full sweep sooner would undermine the one thing this design is for: never
    // bursting past a modest, predictable request rate against a shared account quota.
    @Override public boolean scheduleCycleComplete() { return true; }

    @Override
    public void ingest(SourceContext context, Consumer<Payload> output) throws Exception {
        var state = repository.state();
        var batch = repository.nextProducts(state.catalogueCursor, settings.maxProductsPerRun(), settings);
        for (var product : batch.products()) {
            context.check();
            priceOne(context, product, output);
            state.catalogueCursor = product.id();
            repository.save(state);
        }
        if (batch.wrapped()) {
            // A full sweep just completed this run (or the catalogue is smaller than one
            // batch) - reset to the very start rather than leaving the cursor at whatever id
            // the wrapped portion happened to end on, which would silently skip the
            // beginning of the catalogue on every future run.
            state.catalogueCursor = 0;
            repository.save(state);
        }
    }

    /**
     * No catch here, deliberately - unlike SearchApiSource's discovery step (which has a real
     * "no match for this product" IngestionFailure code it legitimately swallows in untargeted
     * mode), SearchApiClient.ebay() has no such code: an empty organic_results array already
     * resolves to an empty, harmless result through the normal return path, no exception
     * involved. Every exception client.ebay() can actually throw - auth failure, malformed
     * response, HTTP failure, SourceContext's own RetryLater/TransportFailure/deadline checks -
     * represents a real problem with the run, not a per-product non-match, and must propagate
     * to the orchestrator the same way every other source's real failures do (fails the run,
     * does not advance the schedule interval, applies the normal failure cooldown/retry-later
     * handling - see IngestionOrchestrator). Swallowing all of those here (as an earlier version
     * of this method did) would silently convert an auth failure or outage into an apparently
     * successful, schedule-advancing run that priced nothing.
     */
    private void priceOne(SourceContext context, EbayPriceRepository.Product product, Consumer<Payload> output)
            throws Exception {
        JsonNode results = client.ebay(context, product.name());
        toPrice(product.id(), product.category(), product.brand(), product.modelName(), context.now(), results)
                .ifPresent(output::accept);
    }

    /**
     * Pure results-JsonNode -> Payload transform, no network - split out from {@link #priceOne}
     * specifically so it's directly unit-testable against fixture JSON, same split as every
     * other source in this package (MobileApiSmartphoneSource.toPayload, etc). category picks
     * ProductMatcher's listing vocabulary (SMARTPHONE or GPU - see its titleMatches overload),
     * same reasoning SearchApiSource already applies to Google Shopping matching.
     */
    static Optional<Payload> toPrice(long productId, String category, String brand, String modelName,
                                      Instant observedAt, JsonNode results) {
        var matches = ProductMatcher.titleMatches(category, brand, modelName, results);
        // Picks the minimum price among every accepted, priced match - does NOT trust the
        // results are already sorted cheapest-first. SearchApiClient.ebay()'s sort_by hint is
        // not honored on every eBay marketplace (confirmed: SearchAPI.io's own docs state
        // price_low_to_high is unsupported on ebay.com, the default domain here), so taking
        // merely the first priced result would silently pick an arbitrary, not necessarily
        // cheapest, listing.
        var priced = matches.stream().filter(m -> m.price() != null && m.currency() != null)
                .min(java.util.Comparator.comparing(ProductMatcher.Match::price));
        if (priced.isEmpty()) return Optional.empty();
        var match = priced.get();
        return Optional.of(new Payload(ID, productId + ":ebay:" + observedAt.toEpochMilli(), observedAt,
                new Payload.Price(Long.toString(productId), null, match.price(), match.currency())));
    }
}
