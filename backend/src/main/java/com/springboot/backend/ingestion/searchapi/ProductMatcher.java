package com.springboot.backend.ingestion.searchapi;

import com.springboot.backend.ingestion.IngestionFailure;
import static com.springboot.backend.ingestion.IngestionFailure.Code.*;
import java.text.Normalizer;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Fail closed on unknown suffixes and equally specific distinct Google identities. */
public final class ProductMatcher {
    private ProductMatcher() {}
    public record Match(String externalId, String token, String title) {}
    private static final Set<String> REJECT = Set.of("case", "cover", "protector", "screen", "charger",
            "cable", "replacement", "refurbished", "renewed", "used", "preowned", "pre", "bundle", "replica");
    private static final Set<String> SUFFIX = Set.of("unlocked", "locked", "new", "smartphone", "phone",
            "5g", "4g", "gb", "tb", "black", "white", "blue", "green", "pink", "purple", "red", "gold",
            "silver", "gray", "grey", "titanium", "natural", "desert", "midnight", "starlight",
            "dual", "sim", "esim", "singtel", "starhub", "m1", "verizon", "at", "t", "mobile");
    static List<String> tokens(String value) {
        return Arrays.stream(Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replace("+", " plus ").replaceAll("[^\\p{L}\\p{N}]+", " ").trim().split(" +"))
                .filter(s -> !s.isBlank()).toList();
    }
    public static boolean accepts(String brand, String model, String title) {
        List<String> b = tokens(brand), m = tokens(model), t = tokens(title);
        if (b.isEmpty() || m.isEmpty() || !t.containsAll(b) || !t.containsAll(m)
                || t.stream().anyMatch(REJECT::contains)) return false;
        // Ordered, contiguous model tokens prevent mixed-model titles matching a bag of words.
        if (!String.join(" ", t).contains(String.join(" ", m))) return false;
        var expected = new HashSet<>(b); expected.addAll(m);
        if (expected.stream().anyMatch(token -> Collections.frequency(t, token) > 1)) return false;
        int core = 0;
        for (String token : t) {
            if (expected.contains(token)) { core++; continue; }
            if (!SUFFIX.contains(token) && !token.matches("(?:64|128|256|512|1024)(?:gb)?|[124]tb")) return false;
        }
        return (double) core / t.size() >= 0.4;
    }
    public static Match choose(String brand, String model, JsonNode results) {
        var matches = new TreeMap<String, Match>();
        Comparator<Match> specificity = Comparator
                .comparingInt((Match match) -> extraTokenCount(brand, model, match.title()))
                .thenComparing(Match::title);
        for (JsonNode row : results) {
            String title = row.path("title").asText("");
            String id = row.path("product_id").asText("");
            String token = row.path("product_token").asText("");
            if (!id.isBlank() && !token.isBlank() && title.length() <= 1000 && token.length() <= 16000
                    && accepts(brand, model, title)) {
                Match match = new Match(id, token, title);
                matches.merge(id, match, (a, z) -> specificity.compare(a, z) <= 0 ? a : z);
            }
        }
        if (matches.isEmpty()) throw new IngestionFailure(SEARCHAPI_NO_MATCH);
        int bestScore = matches.values().stream().mapToInt(
                match -> extraTokenCount(brand, model, match.title())).min().orElseThrow();
        var best = matches.values().stream().filter(
                match -> extraTokenCount(brand, model, match.title()) == bestScore).toList();
        if (best.size() != 1) throw new IngestionFailure(SEARCHAPI_AMBIGUOUS_MATCH);
        return best.getFirst();
    }

    private static int extraTokenCount(String brand, String model, String title) {
        var expected = new HashSet<>(tokens(brand));
        expected.addAll(tokens(model));
        return (int) tokens(title).stream().filter(token -> !expected.contains(token)).count();
    }
}
