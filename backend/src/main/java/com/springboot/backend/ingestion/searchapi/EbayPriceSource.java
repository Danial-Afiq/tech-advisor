package com.springboot.backend.ingestion.searchapi;

import com.springboot.backend.ingestion.core.IngestionSource;
import com.springboot.backend.ingestion.core.Payload;
import com.springboot.backend.ingestion.core.SourceContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Default config (10 products/run, once every 2 weeks - see scheduleInterval(), matching the
 * shared scheduler cadence in RunStore.INTERVAL) costs ~20 requests a month against the shared
 * SearchAPI.io account quota - deliberately conservative given that quota is shared with
 * Google Shopping review ingestion too and has been exhausted mid-month before (Smartphone DB
 * Backfill session). Both numbers are plain config, tune freely.
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
    private static final Logger LOG = LoggerFactory.getLogger(EbayPriceSource.class);
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
    // Matches the shared scheduler cadence (RunStore.INTERVAL) - see class Javadoc for the
    // request-budget math at this interval.
    @Override public Optional<Duration> scheduleInterval() { return Optional.of(Duration.ofDays(14)); }
    // Always true: unlike TechApiBenchmarkSource's one-time backfill-then-incremental shape,
    // this source has no "finished" state to race toward faster - every run prices its own
    // bounded batch and that is intentionally the whole job for that run. Returning false to
    // chase a full sweep sooner would undermine the one thing this design is for: never
    // bursting past a modest, predictable request rate against a shared account quota.
    @Override public boolean scheduleCycleComplete() { return true; }

    @Override
    public void ingest(SourceContext context, Consumer<Payload> output) throws Exception {
        var state = repository.state();
        var batch = repository.nextProducts(state.catalogueCursor, settings.maxProductsPerRun());
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

    private void priceOne(SourceContext context, EbayPriceRepository.Product product, Consumer<Payload> output)
            throws Exception {
        JsonNode results;
        try {
            results = client.ebay(context, product.name());
        } catch (Exception e) {
            // One product's listing failure is not a run failure - a bad/empty eBay result
            // for one phone is a legitimate real-world outcome (same reasoning
            // SearchApiSource's untargeted no-match path already applies to Google
            // Shopping), not something that should abort the rest of this run's batch.
            LOG.info("eBay price lookup failed for product {} ({}): {}",
                    product.id(), product.name(), e.getClass().getSimpleName());
            return;
        }
        toPrice(product.id(), product.brand(), product.modelName(), context.now(), results).ifPresent(output::accept);
    }

    /**
     * Pure results-JsonNode -> Payload transform, no network - split out from {@link #priceOne}
     * specifically so it's directly unit-testable against fixture JSON, same split as every
     * other source in this package (MobileApiSmartphoneSource.toPayload, etc).
     */
    static Optional<Payload> toPrice(long productId, String brand, String modelName, Instant observedAt, JsonNode results) {
        var matches = ProductMatcher.titleMatches(brand, modelName, results);
        // Already sorted price_low_to_high by the query itself (SearchApiClient.ebay) - the
        // first match with a real parsed price is the representative "new, cheapest" figure.
        var priced = matches.stream().filter(m -> m.price() != null && m.currency() != null).findFirst();
        if (priced.isEmpty()) return Optional.empty();
        var match = priced.get();
        return Optional.of(new Payload(ID, productId + ":ebay:" + observedAt.toEpochMilli(), observedAt,
                new Payload.Price(Long.toString(productId), null, match.price(), match.currency())));
    }
}
