package com.springboot.backend.ingestion.searchapi;

import com.springboot.backend.ingestion.*;
import static com.springboot.backend.ingestion.IngestionFailure.Code.*;
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
        return fetch(context, Map.of("engine", "google_shopping", "q", query), "shopping_results");
    }
    public JsonNode reviews(SourceContext context, String token, String sort) throws Exception {
        return fetch(context, Map.of("engine", "google_product_reviews", "product_token", token,
                "sort_by", sort, "rating", "all"), "review_results");
    }
    private JsonNode fetch(SourceContext context, Map<String, String> parameters, String field) throws Exception {
        var query = new TreeMap<>(parameters);
        query.put("gl", settings.gl()); query.put("hl", settings.hl()); query.put("location", settings.location());
        String encoded = query.entrySet().stream().map(e -> e.getKey() + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
        byte[] bytes;
        try {
            bytes = context.get(URI.create("https://www.searchapi.io/api/v1/search?" + encoded),
                    settings.apiKey(), "www.searchapi.io");
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
            JsonNode results = root.path(field);
            if (results.isMissingNode() || results.isNull()) return json.createArrayNode();
            if (!results.isArray()) throw new IngestionFailure(SEARCHAPI_MALFORMED_RESPONSE);
            return results;
        } catch (IngestionFailure e) { throw e; }
        catch (Exception e) { throw new IngestionFailure(SEARCHAPI_MALFORMED_RESPONSE); }
    }
}
