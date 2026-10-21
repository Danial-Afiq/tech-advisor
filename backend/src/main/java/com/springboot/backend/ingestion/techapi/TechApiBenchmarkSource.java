package com.springboot.backend.ingestion.techapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springboot.backend.ingestion.core.IngestionFailure;
import com.springboot.backend.ingestion.core.IngestionSource;
import com.springboot.backend.ingestion.core.Payload;
import com.springboot.backend.ingestion.core.SourceContext;
import java.math.BigDecimal;
import java.net.URI;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Raw Geekbench observations from TechAPI's released GitHub dataset. */
@Component
public class TechApiBenchmarkSource implements IngestionSource {
    public static final String ID = "techapi-soc-benchmarks";
    static final String GEEKBENCH_SINGLE =
            "Geekbench (version unspecified) single-core [representative SoC]";
    static final String GEEKBENCH_MULTI =
            "Geekbench (version unspecified) multi-core [representative SoC]";

    private static final Logger LOG = LoggerFactory.getLogger(TechApiBenchmarkSource.class);
    private static final String API_TREE =
            "https://api.github.com/repos/GetTechAPI/TechAPI/git/trees/";
    private static final String RAW =
            "https://raw.githubusercontent.com/GetTechAPI/TechAPI/main/";
    private static final String SMARTPHONES = RAW + "site/public/v1/smartphones/";
    private static final String SOC_DATA = RAW + "data/soc/";
    private static final Pattern SAFE_SOC_PATH = Pattern.compile(
            "[a-z0-9-]+/[0-9]{4}/[a-z0-9-]+\\.json");
    private static final Set<String> GENERIC_CHIPSET_TOKENS = Set.of(
            "soc", "chipset", "processor", "mobile", "platform");

    private final TechApiBenchmarkRepository repository;
    private final String githubToken;
    private final int maxProducts;
    private final ObjectMapper json = new ObjectMapper();

    public TechApiBenchmarkSource(
            TechApiBenchmarkRepository repository,
            @Value("${sources.techapi.github-token:}") String githubToken,
            @Value("${sources.techapi.max-products-per-run:20}") int maxProducts) {
        if (maxProducts < 1 || maxProducts > 100)
            throw new IllegalArgumentException("TechAPI max products per run must be 1-100");
        this.repository = repository;
        this.githubToken = githubToken == null ? "" : githubToken.trim();
        this.maxProducts = maxProducts;
    }

    @Override public String sourceId() { return ID; }
    @Override public int priority() { return 20; }
    @Override public Optional<Duration> scheduleInterval() { return Optional.of(Duration.ofDays(14)); }

    @Override
    public void ingest(SourceContext context, Consumer<Payload> output) throws Exception {
        var batch = repository.nextPhones(maxProducts);
        if (batch.phones().isEmpty()) return;

        SocTree tree = fetchSocTree(context);
        Map<String, Optional<SocBenchmarks>> benchmarkCache = new HashMap<>();
        int completed = 0;

        for (var phone : batch.phones()) {
            context.check();
            if (context.remainingRequests() == 0) break;

            Optional<PhoneReference> reference;
            try { reference = fetchPhoneReference(context, phone); }
            catch (RequestBudgetExhausted exhausted) { break; }
            if (reference.isEmpty()) {
                skip(phone, "phone-record-unavailable");
                repository.checkpoint(phone.productId());
                completed++;
                continue;
            }
            if (!matches(phone, reference.get())) {
                skip(phone, "phone-or-chipset-mismatch");
                repository.checkpoint(phone.productId());
                completed++;
                continue;
            }

            List<String> paths = tree.paths(reference.get().socSlug());
            if (paths.size() != 1) {
                skip(phone, paths.isEmpty() ? "soc-record-missing" : "soc-record-ambiguous");
                repository.checkpoint(phone.productId());
                completed++;
                continue;
            }

            String path = paths.getFirst();
            Optional<SocBenchmarks> benchmarks = benchmarkCache.get(path);
            if (benchmarks == null) {
                if (context.remainingRequests() == 0) break;
                benchmarks = parseSoc(json.readTree(context.get(URI.create(SOC_DATA + path))),
                        reference.get().socSlug(), context.now());
                benchmarkCache.put(path, benchmarks);
            }

            if (benchmarks.isEmpty()) {
                skip(phone, "geekbench-scores-unavailable");
            } else {
                emit(phone, path, benchmarks.get(), output);
            }
            repository.checkpoint(phone.productId());
            completed++;
        }

        if (completed == batch.phones().size() && !batch.hasMore()) repository.restartCycle();
    }

    private SocTree fetchSocTree(SourceContext context) throws Exception {
        JsonNode root = apiJson(context, "main");
        String dataSha = treeSha(root, "data");
        String socSha = treeSha(apiJson(context, dataSha), "soc");
        return SocTree.parse(apiJson(context, socSha + "?recursive=1"));
    }

    private JsonNode apiJson(SourceContext context, String tree) throws Exception {
        URI uri = URI.create(API_TREE + tree);
        byte[] body = githubToken.isBlank()
                ? context.get(uri)
                : context.get(uri, githubToken, "api.github.com");
        return json.readTree(body);
    }

    private Optional<PhoneReference> fetchPhoneReference(
            SourceContext context, TechApiBenchmarkRepository.PhoneTarget phone) throws Exception {
        String fullModel = phone.modelName();
        String withoutBrand = fullModel;
        if (fullModel != null && phone.brand() != null
                && fullModel.regionMatches(true, 0, phone.brand(), 0, phone.brand().length())
                && fullModel.length() > phone.brand().length()
                && Character.isWhitespace(fullModel.charAt(phone.brand().length()))) {
            withoutBrand = fullModel.substring(phone.brand().length()).trim();
        }
        var candidateSlugs = new LinkedHashSet<>(List.of(slug(withoutBrand), slug(fullModel)));
        candidateSlugs.remove("");
        for (String modelSlug : candidateSlugs) {
            if (context.remainingRequests() == 0) throw new RequestBudgetExhausted();
            try {
                JsonNode document = json.readTree(context.get(
                        URI.create(SMARTPHONES + modelSlug + "/index.json")));
                Optional<PhoneReference> parsed = parsePhone(document);
                if (parsed.isPresent() && matches(phone, parsed.get())) return parsed;
            } catch (SourceContext.HttpFailure missing) {
                if (missing.status != 404) throw missing;
            }
        }
        return Optional.empty();
    }

    static Optional<PhoneReference> parsePhone(JsonNode document) {
        if (document == null || !document.path("verified").asBoolean(false)) return Optional.empty();
        String brand = text(document.path("brand"), "name");
        String model = text(document, "name");
        String socSlug = text(document.path("soc"), "slug");
        String socName = text(document.path("soc"), "name");
        if (brand == null || model == null || socSlug == null || socName == null
                || !socSlug.matches("[a-z0-9-]+")) return Optional.empty();
        return Optional.of(new PhoneReference(brand, model, socSlug, socName));
    }

    static boolean matches(
            TechApiBenchmarkRepository.PhoneTarget local, PhoneReference remote) {
        if (!identity(local.brand()).equals(identity(remote.brand()))) return false;
        if (!modelIdentity(local.modelName(), local.brand())
                .equals(modelIdentity(remote.modelName(), remote.brand()))) return false;
        Set<String> localKeys = chipsetKeys(local.chipset());
        Set<String> remoteKeys = chipsetKeys(remote.socName());
        remoteKeys.add(remote.socSlug());
        return localKeys.stream().anyMatch(remoteKeys::contains);
    }

    static Optional<SocBenchmarks> parseSoc(JsonNode document, String expectedSlug, Instant retrievedAt) {
        if (document == null || !document.path("verified").asBoolean(false)
                || !expectedSlug.equals(text(document, "slug"))) return Optional.empty();
        BigDecimal single = positiveDecimal(document, "geekbench_single");
        BigDecimal multi = positiveDecimal(document, "geekbench_multi");
        if (single == null && multi == null) return Optional.empty();
        return Optional.of(new SocBenchmarks(single, multi,
                datasetTimestamp(text(document, "updated_at"), retrievedAt)));
    }

    private void emit(
            TechApiBenchmarkRepository.PhoneTarget phone,
            String path,
            SocBenchmarks benchmarks,
            Consumer<Payload> output) {
        String product = Long.toString(phone.productId());
        String provenance = "TechAPI GitHub main data/soc/" + path
                + "; representative processor-level score; CC BY-SA 4.0";
        if (benchmarks.single() != null) output.accept(new Payload(
                ID, phone.productId() + ":" + path + ":single", benchmarks.observedAt(),
                new Payload.Benchmark(product, GEEKBENCH_SINGLE, benchmarks.single(),
                        "points", true, provenance)));
        if (benchmarks.multi() != null) output.accept(new Payload(
                ID, phone.productId() + ":" + path + ":multi", benchmarks.observedAt(),
                new Payload.Benchmark(product, GEEKBENCH_MULTI, benchmarks.multi(),
                        "points", true, provenance)));
    }

    private static String treeSha(JsonNode tree, String path) {
        for (JsonNode entry : tree.path("tree")) {
            if (path.equals(text(entry, "path")) && "tree".equals(text(entry, "type"))) {
                String sha = text(entry, "sha");
                if (sha != null && sha.matches("[a-f0-9]{40}")) return sha;
            }
        }
        throw new IngestionFailure(IngestionFailure.Code.TECHAPI_MALFORMED_DATASET);
    }

    static final class SocTree {
        private final Map<String, List<String>> bySlug;
        private SocTree(Map<String, List<String>> bySlug) { this.bySlug = bySlug; }

        static SocTree parse(JsonNode tree) {
            if (tree == null || !tree.path("tree").isArray())
                throw new IngestionFailure(IngestionFailure.Code.TECHAPI_MALFORMED_DATASET);
            if (tree.path("truncated").asBoolean(false))
                throw new IngestionFailure(IngestionFailure.Code.TECHAPI_TRUNCATED_DATASET);
            Map<String, List<String>> bySlug = new HashMap<>();
            for (JsonNode entry : tree.path("tree")) {
                String path = text(entry, "path");
                if (!"blob".equals(text(entry, "type")) || path == null
                        || !SAFE_SOC_PATH.matcher(path).matches()) continue;
                String file = path.substring(path.lastIndexOf('/') + 1, path.length() - 5);
                bySlug.computeIfAbsent(file, ignored -> new ArrayList<>()).add(path);
            }
            return new SocTree(bySlug);
        }

        List<String> paths(String slug) { return bySlug.getOrDefault(slug, List.of()); }
    }

    record PhoneReference(String brand, String modelName, String socSlug, String socName) {}
    record SocBenchmarks(BigDecimal single, BigDecimal multi, Instant observedAt) {}
    private static final class RequestBudgetExhausted extends RuntimeException {}

    private static Set<String> chipsetKeys(String value) {
        Set<String> keys = new LinkedHashSet<>();
        String base = slug(value);
        if (base.isBlank()) return keys;
        keys.add(base);
        List<String> tokens = new ArrayList<>(List.of(base.split("-")));
        if (!tokens.isEmpty() && Set.of("qualcomm", "mediatek", "samsung", "unisoc", "spreadtrum")
                .contains(tokens.getFirst())) tokens.removeFirst();
        tokens.removeIf(token -> GENERIC_CHIPSET_TOKENS.contains(token)
                || token.matches("(?:sm|mt)\\d+[a-z]*"));
        if (!tokens.isEmpty()) keys.add(String.join("-", tokens));
        return keys;
    }

    private static String identity(String value) {
        return slug(value).replace("-", "");
    }

    private static String modelIdentity(String model, String brand) {
        if (model != null && brand != null
                && model.regionMatches(true, 0, brand, 0, brand.length())
                && (model.length() == brand.length()
                    || Character.isWhitespace(model.charAt(brand.length())))) {
            model = model.substring(brand.length()).trim();
        }
        return identity(model);
    }

    private static String slug(String value) {
        if (value == null) return "";
        String ascii = Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "").toLowerCase(java.util.Locale.ROOT);
        return ascii.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()
                ? null : value.asText().trim();
    }

    private static BigDecimal positiveDecimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) return null;
        BigDecimal decimal = value.decimalValue();
        return decimal.signum() > 0 ? decimal : null;
    }

    private static Instant datasetTimestamp(String value, Instant fallback) {
        if (value == null) return fallback;
        try { return Instant.parse(value); }
        catch (DateTimeParseException noOffset) {
            try { return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC); }
            catch (DateTimeParseException invalid) { return fallback; }
        }
    }

    private static void skip(TechApiBenchmarkRepository.PhoneTarget phone, String reason) {
        LOG.info("Skipping TechAPI benchmark enrichment for product {}: {}", phone.productId(), reason);
    }
}
