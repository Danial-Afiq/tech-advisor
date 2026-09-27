package com.springboot.backend.ingestion.searchapi;

import com.springboot.backend.ingestion.config.IngestionSettings;
import com.springboot.backend.ingestion.core.IngestionFailure;
import com.springboot.backend.ingestion.core.IngestionSource;
import com.springboot.backend.ingestion.core.Payload;
import com.springboot.backend.ingestion.core.SourceContext;
import com.springboot.backend.ingestion.searchapi.ProductMatcher.ProductName;
import static com.springboot.backend.ingestion.core.IngestionFailure.Code.*;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@EnableConfigurationProperties(SearchApiSettings.class)
public class SearchApiSource implements IngestionSource {
    public static final String ID = "searchapi-google-product-reviews";
    public static final String PROVIDER = "SEARCHAPI_GOOGLE_SHOPPING";
    private final SearchApiSettings settings;
    private final SearchApiRepository repository;
    private final SearchApiClient client;
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
        return ProductMatcher.candidates(requested.brand(), requested.model(),
                client.shopping(context, requested.canonicalName()));
    }
    @Override public void ingest(SourceContext context, Consumer<Payload> output) throws Exception {
        // MobileAPI is the sole source of truth for `products` rows - a target with no productId
        // means the controller couldn't resolve an existing catalogue match, and this never creates
        // one itself (see ProductMatcher.matchCatalogue / SearchApiRepository.namedProducts). Falls
        // through to the same NO_ELIGIBLE_PRODUCT failure as any other unmatched target.
        var products = context.product() == null ? repository.products(settings.maxProductsPerRun())
                : context.product().productId() == null ? List.<SearchApiRepository.Product>of()
                : repository.eligibleProduct(context.product().productId()).stream()
                    .filter(p -> p.name().equals(context.product().productName())).toList();
        if (products.isEmpty()) throw new IngestionFailure(SEARCHAPI_NO_ELIGIBLE_PRODUCT);
        for (var product : products) {
            context.check();
            String selectedExternalId = context.product() == null ? null : context.product().externalProductId();
            var cached = selectedExternalId == null ? repository.token(product, settings)
                    : java.util.Optional.<String>empty();
            String token = cached.isPresent() ? cached.get() : discover(context, product, selectedExternalId);
            JsonNode[] pages;
            try { pages = fetch(context, token); }
            catch (IngestionFailure failure) {
                if (failure.code() != SEARCHAPI_INVALID_TOKEN) throw failure;
                repository.invalidate(product, settings);
                if (cached.isEmpty()) throw failure;
                // Only a cached token gets one rediscovery. Never loop over invalid responses.
                token = discover(context, product, null);
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
    private String discover(SourceContext context, SearchApiRepository.Product product, String externalProductId) throws Exception {
        var results = client.shopping(context, product.name());
        var match = externalProductId == null ? ProductMatcher.choose(product.brand(), product.model(), results)
                : ProductMatcher.choose(product.brand(), product.model(), results, externalProductId);
        context.check(); repository.cache(product, settings, match, context.now());
        return match.token();
    }
    private JsonNode[] fetch(SourceContext context, String token) throws Exception {
        return new JsonNode[] {client.reviews(context, token, "most_relevant"),
                client.reviews(context, token, "most_recent")};
    }
}
