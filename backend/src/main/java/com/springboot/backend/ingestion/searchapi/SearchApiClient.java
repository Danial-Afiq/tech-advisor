package com.springboot.backend.ingestion.searchapi;

import com.springboot.backend.ingestion.core.IngestionFailure;
import com.springboot.backend.ingestion.core.SourceContext;
import static com.springboot.backend.ingestion.core.IngestionFailure.Code.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SearchApiClient {
    private final SearchApiSettings settings;
    private final JsonMapper json = JsonMapper.builder().build();
    public SearchApiClient(SearchApiSettings settings) { this.settings = settings; }

    public JsonNode shopping(SourceContext context, String query) throws Exception {
        JsonNode root = fetchRoot(context, Map.of("engine", "google_shopping", "q", query));
        var results = json.createArrayNode();
        appendArray(root, "shopping_results", results);

        JsonNode categories = root.path("categorized_shopping_results");
        if (!categories.isMissingNode() && !categories.isNull()) {
            if (!categories.isArray()) throw new IngestionFailure(SEARCHAPI_MALFORMED_RESPONSE);
            for (JsonNode category : categories) {
                if (!category.isObject()) throw new IngestionFailure(SEARCHAPI_MALFORMED_RESPONSE);
                appendArray(category, "shopping_results", results);
            }
        }
        return results;
    }
    public JsonNode reviews(SourceContext context, String token, String sort) throws Exception {
        return fetch(context, Map.of("engine", "google_product_reviews", "product_token", token,
                "sort_by", sort, "rating", "all"), "review_results");
    }
    /**
     * ebay_search, not a Google-based engine - confirmed live it uses its own locale
     * parameters (ebay_domain/country), not gl/hl/location, so this skips
     * {@link #googleLocale} entirely rather than sending Google-specific params an eBay
     * engine would just ignore. condition=new and sort_by=price_low_to_high are deliberate:
     * a consistent "new, cheapest first" baseline is what makes successive observations
     * comparable as a price trend, the same reasoning Google Shopping's own matching already
     * rejects used/refurbished listings for (ProductMatcher's REJECT set).
     */
    public JsonNode ebay(SourceContext context, String query) throws Exception {
        return fetch(context, Map.of("engine", "ebay_search", "q", query,
                "condition", "new", "sort_by", "price_low_to_high"), "organic_results");
    }
    private JsonNode fetch(SourceContext context, Map<String, String> parameters, String field) throws Exception {
        JsonNode root = fetchRoot(context, parameters);
        JsonNode results = root.path(field);
        if (results.isMissingNode() || results.isNull()) return json.createArrayNode();
        if (!results.isArray()) throw new IngestionFailure(SEARCHAPI_MALFORMED_RESPONSE);
        return results;
    }
    private JsonNode fetchRoot(SourceContext context, Map<String, String> parameters) throws Exception {
        var query = new TreeMap<>(parameters);
        if (!"ebay_search".equals(parameters.get("engine"))) googleLocale(query);
        String encoded = query.entrySet().stream().map(e -> e.getKey() + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
        byte[] bytes;
        try {
            bytes = context.get(URI.create(settings.baseUrl() + "?" + encoded),
                    settings.apiKey(), settings.allowedHost());
        } catch (SourceContext.HttpFailure e) {
            if (e.invalidToken) throw new IngestionFailure(SEARCHAPI_INVALID_TOKEN);
            throw new IngestionFailure(switch (e.status) {
                case 400 -> SEARCHAPI_HTTP_400;
                case 401, 403 -> SEARCHAPI_AUTH_FAILED;
                default -> SEARCHAPI_HTTP_FAILED;
            });
        }
        try {
            JsonNode root = json.readTree(bytes);
            if (root == null || !root.isObject() || root.has("error"))
                throw new IngestionFailure(SEARCHAPI_MALFORMED_RESPONSE);
            return root;
        } catch (IngestionFailure e) { throw e; }
        catch (Exception e) { throw new IngestionFailure(SEARCHAPI_MALFORMED_RESPONSE); }
    }
    private void googleLocale(TreeMap<String, String> query) {
        query.put("gl", settings.gl()); query.put("hl", settings.hl()); query.put("location", settings.location());
    }
    private void appendArray(JsonNode parent, String field, tools.jackson.databind.node.ArrayNode output) {
        JsonNode rows = parent.path(field);
        if (rows.isMissingNode() || rows.isNull()) return;
        if (!rows.isArray()) throw new IngestionFailure(SEARCHAPI_MALFORMED_RESPONSE);
        rows.forEach(output::add);
    }
}
