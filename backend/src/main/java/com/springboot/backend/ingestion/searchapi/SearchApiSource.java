package com.springboot.backend.ingestion.searchapi;

import com.springboot.backend.ingestion.config.IngestionSettings;
import com.springboot.backend.ingestion.core.IngestionFailure;
import com.springboot.backend.ingestion.core.IngestionSource;
import com.springboot.backend.ingestion.core.Payload;
import com.springboot.backend.ingestion.core.SourceContext;
import com.springboot.backend.ingestion.searchapi.ProductMatcher.ProductName;
import static com.springboot.backend.ingestion.core.IngestionFailure.Code.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@EnableConfigurationProperties(SearchApiSettings.class)
public class SearchApiSource implements IngestionSource {
    public static final String ID = "searchapi-google-product-reviews";
    public static final String PROVIDER = "SEARCHAPI_GOOGLE_SHOPPING";
    private static final Duration CANDIDATE_TTL = Duration.ofMinutes(10);
    private final SearchApiSettings settings;
    private final SearchApiRepository repository;
    private final SearchApiClient client;
    // Deliberately process-local for the single-instance MVP. A miss retains the safe
    // worker-side discovery path, while the normal picker flow avoids a second live search.
    private final Map<CandidateKey, CandidateEntry> candidateTokens = new ConcurrentHashMap<>();
    private record CandidateKey(String productName, String externalProductId) {}
    private record CandidateEntry(ProductMatcher.Match match, Instant expiresAt) {}
    public SearchApiSource(SearchApiSettings settings, SearchApiRepository repository,
                           SearchApiClient client, IngestionSettings ingestion) {
        this.settings = settings; this.repository = repository; this.client = client;
        if (ingestion.enabledSources().contains(ID) && settings.apiKey().isBlank())
            throw new IllegalArgumentException("SEARCHAPI_API_KEY is required when SearchAPI ingestion is enabled");
    }
    @Override public String sourceId() { return ID; }
    @Override public Duration cooldown() { return Duration.ZERO; }
    public List<ProductMatcher.Candidate> findCandidates(SourceContext context, ProductName requested)
            throws Exception {
        var matches = ProductMatcher.candidateMatches(requested.brand(), requested.model(),
                client.shopping(context, requested.canonicalName()));
        Instant now = context.now();
        candidateTokens.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        Instant expiresAt = now.plus(CANDIDATE_TTL);
        for (var match : matches)
            candidateTokens.put(new CandidateKey(requested.canonicalName(), match.externalId()),
                    new CandidateEntry(match, expiresAt));
        return matches.stream().map(match ->
                new ProductMatcher.Candidate(match.externalId(), match.title())).toList();
    }
    @Override public void ingest(SourceContext context, Consumer<Payload> output) throws Exception {
        // MobileAPI is the sole source of truth for `products` rows - a target with no productId
        // means the controller couldn't resolve an existing catalogue match, and this never creates
        // one itself (see ProductMatcher.matchCatalogue / SearchApiRepository.namedProducts). Falls
        // through to the same NO_ELIGIBLE_PRODUCT failure as any other unmatched target.
        boolean untargeted = context.product() == null;
        // Untargeted mode excludes products already attempted for this provider/locale (VALID
        // or INVALID) - otherwise it re-picks the same oldest-by-id product forever once one
        // exists, and never advances through the rest of the catalogue.
        var products = untargeted ? repository.products(settings)
                : context.product().productId() == null ? List.<SearchApiRepository.Product>of()
                : repository.eligibleProduct(context.product().productId()).stream()
                    .filter(p -> p.name().equals(context.product().productName())).toList();
        if (products.isEmpty()) throw new IngestionFailure(SEARCHAPI_NO_ELIGIBLE_PRODUCT);
        for (var product : products) {
            context.check();
            String selectedExternalId = context.product() == null ? null : context.product().externalProductId();
            var preview = selectedExternalId == null ? Optional.<ProductMatcher.Match>empty()
                    : takeCandidate(product.name(), selectedExternalId, context.now());
            var cached = selectedExternalId == null ? repository.token(product, settings)
                    : java.util.Optional.<String>empty();
            String token;
            if (preview.isPresent()) {
                var match = preview.get();
                context.check(); repository.cache(product, settings, match, context.now());
                token = match.token();
                emitPriceIfKnown(product, match, context, output);
            }
            else if (cached.isPresent()) token = cached.get();
            else {
                try {
                    var match = discover(context, product, selectedExternalId);
                    token = match.token();
                    emitPriceIfKnown(product, match, context, output);
                }
                catch (IngestionFailure noMatch) {
                    // A targeted (admin-named) run should fail loudly - the admin asked for this
                    // exact product. Untargeted mode records the attempt (so the next automatic
                    // run picks a different product instead of retrying this one forever) and
                    // just moves on - no real Google listing for a product is a legitimate
                    // outcome, not an error.
                    if (!untargeted || (noMatch.code() != SEARCHAPI_NO_MATCH && noMatch.code() != SEARCHAPI_AMBIGUOUS_MATCH))
                        throw noMatch;
                    repository.markNoMatch(product, settings, context.now());
                    continue;
                }
            }
            JsonNode[] pages;
            try { pages = fetch(context, token); }
            catch (IngestionFailure failure) {
                if (failure.code() != SEARCHAPI_INVALID_TOKEN) throw failure;
                repository.invalidate(product, settings);
                if (cached.isEmpty()) throw failure;
                // Only a cached token gets one rediscovery. Never loop over invalid responses.
                var rediscovered = discover(context, product, null);
                token = rediscovered.token();
                emitPriceIfKnown(product, rediscovered, context, output);
                try { pages = fetch(context, token); }
                catch (IngestionFailure retry) {
                    if (retry.code() == SEARCHAPI_INVALID_TOKEN) repository.invalidate(product, settings);
                    throw retry;
                }
            }
            context.check();
            repository.verified(product, settings, context.now());
            var reviews = ReviewNormalizer.normalize(product.id(), context.now(), pages);
            if (!reviews.isEmpty()) output.accept(new Payload(ID, Long.toString(product.id()), context.now(),
                    new Payload.ReviewBatch(product.id(), reviews)));
        }
    }
    private Optional<ProductMatcher.Match> takeCandidate(String productName, String externalProductId, Instant now) {
        var entry = candidateTokens.remove(new CandidateKey(productName, externalProductId));
        return entry != null && entry.expiresAt().isAfter(now) ? Optional.of(entry.match()) : Optional.empty();
    }
    private ProductMatcher.Match discover(SourceContext context, SearchApiRepository.Product product,
            String externalProductId) throws Exception {
        var results = client.shopping(context, product.name());
        var match = externalProductId == null ? ProductMatcher.choose(product.brand(), product.model(), results)
                : ProductMatcher.choose(product.brand(), product.model(), results, externalProductId);
        context.check(); repository.cache(product, settings, match, context.now());
        return match;
    }
    /**
     * Price capture only happens here, on the shopping() call discover() already makes for
     * token resolution - never on a cache-hit run, which skips shopping() entirely and costs
     * nothing extra. Trading continuous per-run price refresh for staying inside the existing
     * request budget (AGENTS.md 17.4) is deliberate, not an oversight: always refreshing would
     * add one request to every cached run too. Revisit only as an explicit decision alongside
     * that budget, not silently here.
     */
    private void emitPriceIfKnown(SearchApiRepository.Product product, ProductMatcher.Match match,
            SourceContext context, Consumer<Payload> output) {
        if (match.price() == null || match.currency() == null) return;
        var storageTokens = ProductMatcher.storageGbMentionsInTitle(match.title());
        // Only an unambiguous single storage mention resolves a variant - a title with zero or
        // several storage tokens can't be safely attributed to one exact row, so the price is
        // still recorded, just at the model level (variantId left null).
        Long variantId = storageTokens.size() == 1
                ? repository.variantIdFor(product.id(), storageTokens.getFirst()).orElse(null) : null;
        output.accept(new Payload(ID, product.id() + ":price:" + match.externalId(), context.now(),
                new Payload.Price(Long.toString(product.id()), variantId == null ? null : Long.toString(variantId),
                        match.price(), match.currency())));
    }
    private JsonNode[] fetch(SourceContext context, String token) throws Exception {
        return new JsonNode[] {client.reviews(context, token, "most_relevant"),
                client.reviews(context, token, "most_recent")};
    }
}
