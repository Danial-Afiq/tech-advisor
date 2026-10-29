package com.springboot.backend.ingestion.searchapi;

import com.springboot.backend.ingestion.core.IngestionSource;
import com.springboot.backend.ingestion.core.Payload;
import com.springboot.backend.ingestion.core.SourceContext;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * One-time database upgrade (Oct 2026) - every VERIFIED GPU and every VERIFIED "popular"-brand
 * smartphone (see {@link EbayPriceBackfillSettings}) gets a first eBay price, instead of
 * waiting for {@link EbayPriceSource}'s infinite round-robin to reach each one for the first
 * time, which at that source's own pace (see its Javadoc) can take a very long time for a
 * catalogue this large.
 *
 * Deliberately NOT a variant of {@link EbayPriceSource} with extra parameters: that source is
 * designed to run forever, re-pricing the same products on a cadence, with no notion of "done".
 * This one is designed to run exactly once (or a few bounded times, if the catalogue is larger
 * than one run's {@code maxProductsPerRun}) and then stop permanently - a genuinely different
 * completion shape, closer to {@code TechApiBenchmarkSource}'s one-time backfill phase than to
 * {@code EbayPriceSource}'s steady state. Sharing the matching/price-selection logic
 * ({@link EbayPriceSource#toPrice}) rather than duplicating it, but with its own repository,
 * state, and cursor so this job can never corrupt or race with the daily source's progress.
 *
 * {@code requestLimit()}/{@code executionBudget()} scale with the configured batch size (1
 * request per product, same as {@link EbayPriceSource}), so a single large manually-triggered
 * run - sized via {@code SEARCHAPI_EBAY_BACKFILL_MAX_PRODUCTS_PER_RUN} - can realistically
 * finish the whole backfill in one run: the admin API that starts a run returns immediately
 * (202 Accepted, the run proceeds asynchronously), so there is no HTTP-timeout reason to keep
 * batches small the way an interactive request would need. {@code executionBudget()} is still
 * the real ceiling SourceContext enforces.
 */
@Component
@EnableConfigurationProperties(EbayPriceBackfillSettings.class)
public class EbayPriceBackfillSource implements IngestionSource {
    public static final String ID = "searchapi-ebay-prices-backfill";
    private static final int NETWORK_OVERHEAD_SECONDS = 30;

    private final EbayPriceBackfillRepository repository;
    private final EbayPriceBackfillSettings settings;
    private final SearchApiClient client;

    public EbayPriceBackfillSource(EbayPriceBackfillRepository repository, EbayPriceBackfillSettings settings,
                                    SearchApiClient client) {
        this.repository = repository;
        this.settings = settings;
        this.client = client;
    }

    @Override public String sourceId() { return ID; }
    // Same tier as the daily EbayPriceSource (30) - both only price products that already
    // exist, after catalogue-creation sources (10).
    @Override public int priority() { return 30; }
    @Override public Duration cooldown() { return Duration.ZERO; }
    @Override public int requestLimit() { return settings.maxProductsPerRun(); }
    @Override public Duration executionBudget() {
        return Duration.ofSeconds(requestLimit() + NETWORK_OVERHEAD_SECONDS);
    }
    // Empty: follows the shared scheduler cadence while incomplete, same mechanism
    // TechApiBenchmarkSource's own one-time backfill phase uses (see scheduleCycleComplete()
    // below) - an incomplete run stays eligible at the next shared wake rather than waiting out
    // some independent interval of its own. This source is still meant to be mostly triggered
    // manually for speed; being schedulable too is a safety net, not the primary path.
    @Override public Optional<Duration> scheduleInterval() { return Optional.empty(); }
    // False (not yet complete) keeps this source eligible at the next shared wake instead of
    // waiting out scheduleInterval(); true (persisted, permanent) means it is finished for good
    // and will never be scheduled or do real work again, even if manually re-triggered.
    @Override public boolean scheduleCycleComplete() { return repository.state().completed; }

    @Override public Optional<Progress> progress() {
        var state = repository.state();
        var brands = settings.smartphoneBrands();
        return Optional.of(new Progress(state.completed ? "complete" : "backfilling",
                repository.attemptedCount(state.cursor, brands), repository.targetCount(brands),
                repository.observationsCount(), repository.observationsCount()));
    }

    @Override
    public void ingest(SourceContext context, Consumer<Payload> output) throws Exception {
        var state = repository.state();
        if (state.completed) return; // Finished for good - costs nothing even if re-triggered.
        var batch = repository.nextProducts(state.cursor, settings.maxProductsPerRun(), settings.smartphoneBrands());
        for (var product : batch.products()) {
            context.check();
            priceOne(context, product, output);
            state.cursor = product.id();
            repository.save(state);
        }
        if (batch.exhausted()) {
            // No wraparound (unlike EbayPriceRepository's infinite sweep): every target product
            // has now either been priced or had no acceptable listing. Done, permanently.
            state.completed = true;
            repository.save(state);
        }
    }

    private void priceOne(SourceContext context, EbayPriceRepository.Product product, Consumer<Payload> output)
            throws Exception {
        // Same no-catch reasoning as EbayPriceSource.priceOne(): every exception client.ebay()
        // can throw is a real failure that must abort the run and propagate, never a legitimate
        // "no listing" outcome to silently swallow.
        JsonNode results = client.ebay(context, product.name());
        EbayPriceSource.toPrice(ID, product.id(), product.category(), product.brand(), product.modelName(),
                context.now(), results).ifPresent(output::accept);
    }
}
