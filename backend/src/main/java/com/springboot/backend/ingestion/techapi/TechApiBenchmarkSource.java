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
import java.util.Base64;
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

/** One-time TechAPI Geekbench 6 backfill followed by incremental coverage discovery. */
@Component
public class TechApiBenchmarkSource implements IngestionSource {
    public static final String ID = "techapi-soc-benchmarks";
    static final String GEEKBENCH_SINGLE = "Geekbench 6 CPU Single-Core";
    static final String GEEKBENCH_MULTI = "Geekbench 6 CPU Multi-Core";

    private static final Logger LOG = LoggerFactory.getLogger(TechApiBenchmarkSource.class);
    private static final String API_TREE =
            "https://api.github.com/repos/GetTechAPI/TechAPI/git/trees/";
    private static final String API_BLOB =
            "https://api.github.com/repos/GetTechAPI/TechAPI/git/blobs/";
    private static final String SMARTPHONES =
            "https://raw.githubusercontent.com/GetTechAPI/TechAPI/main/site/public/v1/smartphones/";
    private static final Pattern SAFE_SOC_PATH = Pattern.compile(
            "[a-z0-9-]+/[0-9]{4}/[a-z0-9-]+\\.json");
    private static final Set<String> GENERIC_CHIPSET_TOKENS = Set.of(
            "soc", "chipset", "processor", "mobile", "platform");
    private static final int TREE_REQUESTS = 4;
    private static final int MAX_REQUESTS_PER_ITEM = 3;
    private static final int NETWORK_OVERHEAD_SECONDS = 30;

    private final TechApiBenchmarkRepository repository;
    private final String githubToken;
    private final int maxItems;
    private final ObjectMapper json = new ObjectMapper();

    public TechApiBenchmarkSource(
            TechApiBenchmarkRepository repository,
            @Value("${sources.techapi.github-token:}") String githubToken,
            @Value("${sources.techapi.max-products-per-run:20}") int maxItems) {
        if (maxItems < 1 || maxItems > 100)
            throw new IllegalArgumentException("TechAPI max items per run must be 1-100");
        this.repository = repository;
        this.githubToken = githubToken == null ? "" : githubToken.trim();
        this.maxItems = maxItems;
    }

    @Override public String sourceId() { return ID; }
    @Override public int priority() { return 20; }
    @Override public Duration cooldown() { return Duration.ZERO; }
    @Override public int requestLimit() { return TREE_REQUESTS + maxItems * MAX_REQUESTS_PER_ITEM; }
    @Override public Duration executionBudget() {
        return Duration.ofSeconds(requestLimit() + NETWORK_OVERHEAD_SECONDS);
    }
    @Override public Optional<Duration> scheduleInterval() { return Optional.of(Duration.ofDays(14)); }
    @Override public boolean scheduleCycleComplete() {
        var state = repository.state();
        return state.initialBackfillComplete && state.pendingRevision == null
                && repository.nextPhones(state.catalogueCursor, 1).phones().isEmpty();
    }
    @Override public Optional<Progress> progress() {
        var state = repository.state();
        return Optional.of(new Progress(
                state.phase,
                state.completedUnits,
                state.totalUnits,
                repository.observationCount(),
                repository.coveredProductCount()));
    }

    @Override
    public void ingest(SourceContext context, Consumer<Payload> output) throws Exception {
        var state = repository.state();
        if (!state.initialBackfillComplete) initialBackfill(context, output, state);
        else incremental(context, output, state);
    }

    private void initialBackfill(
            SourceContext context, Consumer<Payload> output, TechApiIngestionState state)
            throws Exception {
        SocTree tree;
        if (state.snapshotRevision == null) {
            state.snapshotRevision = currentSocRevision(context);
            tree = fetchSocTree(context, state.snapshotRevision);
            state.totalUnits = tree.entries().size();
            state.catalogueSnapshotMax = repository.maximumEligibleProductId();
            repository.save(state);
        } else {
            tree = fetchSocTree(context, state.snapshotRevision);
        }

        var phones = repository.phonesThrough(state.catalogueSnapshotMax);
        List<TreeEntry> remaining = tree.after(state.cursor);
        int processed = 0;
        for (TreeEntry entry : remaining) {
            if (processed >= maxItems) break;
            context.check();
            if (tree.entries(entry.slug()).size() == 1) {
                var benchmarks = fetchSoc(context, entry);
                if (benchmarks.isPresent()) {
                    for (var phone : phones)
                        if (processorMatches(phone, entry.slug(), benchmarks.get().name()))
                            emit(phone, entry.path(), benchmarks.get(), output);
                }
            }
            state.cursor = entry.path();
            state.completedUnits = tree.position(entry.path()) + 1L;
            repository.save(state);
            processed++;
        }

        if (tree.after(state.cursor).isEmpty()) {
            state.phase = "INCREMENTAL";
            state.initialBackfillComplete = true;
            state.initialBackfillCompletedAt = context.now();
            state.baselineRevision = state.snapshotRevision;
            state.catalogueCursor = state.catalogueSnapshotMax;
            repository.save(state);
        }
    }

    private void incremental(
            SourceContext context, Consumer<Payload> output, TechApiIngestionState state)
            throws Exception {
        int processed = 0;
        SocTree activeTree = null;

        if (state.pendingRevision == null) {
            String currentRevision = currentSocRevision(context);
            if (!currentRevision.equals(state.baselineRevision)) {
                SocTree baseline = fetchSocTree(context, state.baselineRevision);
                activeTree = fetchSocTree(context, currentRevision);
                var changed = activeTree.changedSince(baseline);
                state.pendingRevision = currentRevision;
                state.pendingCursor = null;
                state.pendingCompletedUnits = 0;
                state.pendingTotalUnits = changed.size();
                repository.save(state);
            }
        }

        if (state.pendingRevision != null) {
            SocTree baseline = fetchSocTree(context, state.baselineRevision);
            if (activeTree == null) activeTree = fetchSocTree(context, state.pendingRevision);
            List<TreeEntry> changed = activeTree.changedSince(baseline);
            List<TreeEntry> remaining = after(changed, state.pendingCursor);
            var localPhones = remaining.isEmpty() ? List.<TechApiBenchmarkRepository.PhoneTarget>of()
                    : repository.allPhones();
            for (TreeEntry entry : remaining) {
                if (processed >= maxItems) break;
                context.check();
                if (activeTree.entries(entry.slug()).size() == 1) {
                    var benchmarks = fetchSoc(context, entry);
                    if (benchmarks.isPresent()) {
                        for (var phone : localPhones)
                            if (processorMatches(phone, entry.slug(), benchmarks.get().name()))
                                emit(phone, entry.path(), benchmarks.get(), output);
                    }
                }
                state.pendingCursor = entry.path();
                state.pendingCompletedUnits++;
                repository.save(state);
                processed++;
            }
            if (after(changed, state.pendingCursor).isEmpty()) {
                state.baselineRevision = state.pendingRevision;
                state.pendingRevision = null;
                state.pendingCursor = null;
                state.pendingCompletedUnits = 0;
                state.pendingTotalUnits = 0;
                repository.save(state);
            }
        }

        int capacity = maxItems - processed;
        if (capacity == 0) return;
        var phones = repository.nextPhones(state.catalogueCursor, capacity);
        if (phones.phones().isEmpty()) return;
        if (activeTree == null) activeTree = fetchSocTree(context, state.baselineRevision);
        Map<String, Optional<SocBenchmarks>> benchmarkCache = new HashMap<>();
        for (var phone : phones.phones()) {
            context.check();
            if (!repository.hasCompleteCoverage(phone.productId())) {
                Optional<PhoneReference> reference = fetchPhoneReference(context, phone);
                if (reference.isEmpty()) {
                    skip(phone, "phone-record-unavailable");
                } else if (!matches(phone, reference.get())) {
                    skip(phone, "phone-or-chipset-mismatch");
                } else {
                    List<TreeEntry> entries = activeTree.entries(reference.get().socSlug());
                    if (entries.size() != 1) {
                        skip(phone, entries.isEmpty() ? "soc-record-missing" : "soc-record-ambiguous");
                    } else {
                        TreeEntry entry = entries.getFirst();
                        Optional<SocBenchmarks> benchmarks = benchmarkCache.computeIfAbsent(
                                entry.sha(), ignored -> uncheckedFetchSoc(context, entry));
                        if (benchmarks.isEmpty()) skip(phone, "geekbench-scores-unavailable");
                        else emit(phone, entry.path(), benchmarks.get(), output);
                    }
                }
            }
            state.catalogueCursor = phone.productId();
            repository.save(state);
        }
    }

    private Optional<SocBenchmarks> uncheckedFetchSoc(SourceContext context, TreeEntry entry) {
        try { return fetchSoc(context, entry); }
        catch (RuntimeException runtime) { throw runtime; }
        catch (Exception checked) { throw new IllegalStateException(checked); }
    }

    private String currentSocRevision(SourceContext context) throws Exception {
        String dataSha = treeSha(apiJson(context, "main"), "data");
        return treeSha(apiJson(context, dataSha), "soc");
    }

    private SocTree fetchSocTree(SourceContext context, String revision) throws Exception {
        if (revision == null || !revision.matches("[a-f0-9]{40}"))
            throw new IngestionFailure(IngestionFailure.Code.TECHAPI_MALFORMED_DATASET);
        return SocTree.parse(apiJson(context, revision + "?recursive=1"));
    }

    private Optional<SocBenchmarks> fetchSoc(SourceContext context, TreeEntry entry) throws Exception {
        JsonNode blob = githubJson(context, URI.create(API_BLOB + entry.sha()));
        String encoding = text(blob, "encoding");
        String content = text(blob, "content");
        if (!"base64".equals(encoding) || content == null)
            throw new IngestionFailure(IngestionFailure.Code.TECHAPI_MALFORMED_DATASET);
        JsonNode document;
        try { document = json.readTree(Base64.getMimeDecoder().decode(content)); }
        catch (IllegalArgumentException invalid) {
            throw new IngestionFailure(IngestionFailure.Code.TECHAPI_MALFORMED_DATASET);
        }
        return parseSoc(document, entry.slug(), context.now());
    }

    private JsonNode apiJson(SourceContext context, String tree) throws Exception {
        return githubJson(context, URI.create(API_TREE + tree));
    }

    private JsonNode githubJson(SourceContext context, URI uri) throws Exception {
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

    static boolean matches(TechApiBenchmarkRepository.PhoneTarget local, PhoneReference remote) {
        if (!identity(local.brand()).equals(identity(remote.brand()))) return false;
        if (!modelIdentity(local.modelName(), local.brand())
                .equals(modelIdentity(remote.modelName(), remote.brand()))) return false;
        return processorMatches(local, remote.socSlug(), remote.socName());
    }

    static boolean processorMatches(
            TechApiBenchmarkRepository.PhoneTarget local, String socSlug, String socName) {
        Set<String> localKeys = chipsetKeys(local.chipset());
        Set<String> remoteKeys = chipsetKeys(socName);
        remoteKeys.add(socSlug);
        return localKeys.stream().anyMatch(remoteKeys::contains);
    }

    static Optional<SocBenchmarks> parseSoc(JsonNode document, String expectedSlug, Instant retrievedAt) {
        if (document == null || !document.path("verified").asBoolean(false)
                || !expectedSlug.equals(text(document, "slug"))) return Optional.empty();
        BigDecimal single = positiveDecimal(document, "geekbench_single");
        BigDecimal multi = positiveDecimal(document, "geekbench_multi");
        if (single == null && multi == null) return Optional.empty();
        return Optional.of(new SocBenchmarks(text(document, "name"), single, multi,
                datasetTimestamp(text(document, "updated_at"), retrievedAt)));
    }

    private void emit(
            TechApiBenchmarkRepository.PhoneTarget phone, String path,
            SocBenchmarks benchmarks, Consumer<Payload> output) {
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
        private final List<TreeEntry> entries;
        private final Map<String, TreeEntry> byPath;
        private final Map<String, List<TreeEntry>> bySlug;

        private SocTree(List<TreeEntry> entries) {
            this.entries = entries;
            this.byPath = new HashMap<>();
            this.bySlug = new HashMap<>();
            for (TreeEntry entry : entries) {
                byPath.put(entry.path(), entry);
                bySlug.computeIfAbsent(entry.slug(), ignored -> new ArrayList<>()).add(entry);
            }
        }

        static SocTree parse(JsonNode tree) {
            if (tree == null || !tree.path("tree").isArray())
                throw new IngestionFailure(IngestionFailure.Code.TECHAPI_MALFORMED_DATASET);
            if (tree.path("truncated").asBoolean(false))
                throw new IngestionFailure(IngestionFailure.Code.TECHAPI_TRUNCATED_DATASET);
            List<TreeEntry> entries = new ArrayList<>();
            for (JsonNode item : tree.path("tree")) {
                String path = text(item, "path");
                String sha = text(item, "sha");
                if (!"blob".equals(text(item, "type")) || path == null || sha == null
                        || !SAFE_SOC_PATH.matcher(path).matches()
                        || !sha.matches("[a-f0-9]{40}")) continue;
                String slug = path.substring(path.lastIndexOf('/') + 1, path.length() - 5);
                entries.add(new TreeEntry(path, sha, slug));
            }
            entries.sort(java.util.Comparator.comparing(TreeEntry::path));
            return new SocTree(List.copyOf(entries));
        }

        List<TreeEntry> entries() { return entries; }
        List<TreeEntry> entries(String slug) { return bySlug.getOrDefault(slug, List.of()); }

        int position(String path) {
            for (int index = 0; index < entries.size(); index++)
                if (entries.get(index).path().equals(path)) return index;
            throw new IngestionFailure(IngestionFailure.Code.TECHAPI_MALFORMED_DATASET);
        }

        List<TreeEntry> after(String cursor) { return TechApiBenchmarkSource.after(entries, cursor); }

        List<TreeEntry> changedSince(SocTree baseline) {
            return entries.stream().filter(entry -> {
                TreeEntry previous = baseline.byPath.get(entry.path());
                return previous == null || !previous.sha().equals(entry.sha());
            }).toList();
        }
    }

    private static List<TreeEntry> after(List<TreeEntry> entries, String cursor) {
        if (cursor == null) return entries;
        for (int index = 0; index < entries.size(); index++)
            if (entries.get(index).path().equals(cursor)) return entries.subList(index + 1, entries.size());
        throw new IngestionFailure(IngestionFailure.Code.TECHAPI_MALFORMED_DATASET);
    }

    record TreeEntry(String path, String sha, String slug) {}
    record PhoneReference(String brand, String modelName, String socSlug, String socName) {}
    record SocBenchmarks(String name, BigDecimal single, BigDecimal multi, Instant observedAt) {}

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

    private static String identity(String value) { return slug(value).replace("-", ""); }

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
