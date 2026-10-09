package com.springboot.backend.ingestion.searchapi;

import com.springboot.backend.ingestion.core.IngestionFailure;
import static com.springboot.backend.ingestion.core.IngestionFailure.Code.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.text.Normalizer;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Fail closed on unknown suffixes and equally specific distinct Google identities. */
public final class ProductMatcher {
    private ProductMatcher() {}
    /** Admin-entered canonical identity: the first word is the brand, the rest is the model. */
    public record ProductName(String brand, String model) {
        public static ProductName parse(String value) {
            String normalized = value == null ? "" : value.replaceAll("[\\p{Z}\\s]+", " ").trim();
            int separator = normalized.indexOf(' ');
            if (separator < 1 || separator == normalized.length() - 1)
                throw new IllegalArgumentException("Enter both the smartphone brand and model");
            return new ProductName(normalized.substring(0, separator), normalized.substring(separator + 1));
        }

        public String canonicalName() { return brand + " " + model; }
    }
    /**
     * price/currency are nullable - a listing with no parseable price still matches on
     * identity, it just can't also seed a price observation. currency is read from the
     * leading symbol of the "price" display string (extracted_price itself is bare, no
     * currency marker) - confirmed live: every result observed here showed "$" regardless of
     * gl=sg/location=Singapore, not a documented API guarantee, so this is read per-listing
     * rather than assumed fixed.
     */
    public record Match(String externalId, String token, String title,
                        java.math.BigDecimal price, String currency) {}
    private static final Map<Character, String> CURRENCY_SYMBOLS =
            Map.of('$', "USD", '€', "EUR", '£', "GBP", '₹', "INR");
    @Schema(name = "SearchApiCandidateResponse", description = "Validated SearchAPI product identity safe to return to an administrator.")
    public record Candidate(
            @Schema(example = "searchapi-product-id") String externalProductId,
            @Schema(example = "Samsung Galaxy S25 256GB") String title) {}
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
        var matches = validMatches(brand, model, results);
        if (matches.isEmpty()) throw new IngestionFailure(SEARCHAPI_NO_MATCH);
        int bestScore = matches.values().stream().mapToInt(
                match -> extraTokenCount(brand, model, match.title())).min().orElseThrow();
        var best = matches.values().stream().filter(
                match -> extraTokenCount(brand, model, match.title()) == bestScore).toList();
        if (best.size() != 1) throw new IngestionFailure(SEARCHAPI_AMBIGUOUS_MATCH);
        return best.getFirst();
    }

    public static Match choose(String brand, String model, JsonNode results, String externalProductId) {
        Match selected = validMatches(brand, model, results).get(externalProductId);
        if (selected == null) throw new IngestionFailure(SEARCHAPI_NO_MATCH);
        return selected;
    }

    public static List<Candidate> candidates(String brand, String model, JsonNode results) {
        return validMatches(brand, model, results).values().stream().limit(20)
                .map(match -> new Candidate(match.externalId(), match.title())).toList();
    }

    private static Map<String, Match> validMatches(String brand, String model, JsonNode results) {
        var matches = new LinkedHashMap<String, Match>();
        Comparator<Match> specificity = Comparator
                .comparingInt((Match match) -> extraTokenCount(brand, model, match.title()))
                .thenComparing(Match::title);
        for (JsonNode row : results) {
            String title = row.path("title").asText("");
            String id = row.path("product_id").asText("");
            String token = row.path("product_token").asText("");
            if (!id.isBlank() && id.length() <= 1000 && !token.isBlank()
                    && title.length() <= 1000 && token.length() <= 16000
                    && accepts(brand, model, title)) {
                Match match = new Match(id, token, title, extractedPrice(row), currencyOf(row));
                matches.merge(id, match, (a, z) -> specificity.compare(a, z) <= 0 ? a : z);
            }
        }
        return matches;
    }

    // Possessive quantifiers (++/*+) on the digit/whitespace runs: each one only ever
    // needs to match greedily with no backtracking into it once past, so forcing that
    // instead of leaving it to the engine avoids the super-linear worst case the plain
    // +/* version has on pathological input (SonarCloud java:S8786) without changing
    // what matches - still "256GB"/"1.5 TB" etc, same as before.
    private static final java.util.regex.Pattern STORAGE_MENTION = java.util.regex.Pattern.compile(
            "(\\d++(?:\\.\\d++)?)\\s*+(GB|TB)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Every storage figure mentioned anywhere in a shopping listing title (e.g. "Apple
     * iPhone 16 Pro 256GB" -> [256]), not just at the start of the string - unlike
     * MobileApiFieldExtractor.storageOptionsGb(), which is anchored for parsing MobileAPI's
     * own bare comma-separated "storage" field and matches nothing against a sentence with
     * the figure embedded mid-string (confirmed: it silently returned empty against a real
     * title here, caught by this method's own test).
     */
    static List<Integer> storageGbMentionsInTitle(String title) {
        if (title == null) return List.of();
        var found = new LinkedHashSet<Integer>();
        var m = STORAGE_MENTION.matcher(title);
        while (m.find()) {
            double value = Double.parseDouble(m.group(1));
            found.add((int) Math.round(m.group(2).equalsIgnoreCase("TB") ? value * 1024 : value));
        }
        return List.copyOf(found);
    }

    private static java.math.BigDecimal extractedPrice(JsonNode row) {
        JsonNode node = row.path("extracted_price");
        if (!node.isNumber()) return null;
        try { return new java.math.BigDecimal(node.asText()); }
        catch (NumberFormatException e) { return null; }
    }
    private static String currencyOf(JsonNode row) {
        String display = row.path("price").asText("");
        return display.isEmpty() ? null : CURRENCY_SYMBOLS.get(display.charAt(0));
    }

    /**
     * Matches a free-text admin-typed name against our OWN catalogue instead
     * of against SearchAPI's Google Shopping titles. Deliberately not built
     * on {@link #accepts} - that method requires a separate brand/model
     * split, but a catalogue lookup must accept either "Brand Model" or a
     * bare "Model" alone (a sufficiently distinctive model name needs no
     * brand prefix) exactly like the exact-string lookup this replaces did,
     * checked via SQL's "brand||model_name" OR "model_name" OR clause.
     * MobileAPI is the sole creator of `products` rows; this only finds an
     * existing one, never creates one.
     */
    public static boolean matchesCatalogueName(String rawName, String candidateText) {
        List<String> q = tokens(rawName), t = tokens(candidateText);
        if (q.isEmpty() || t.isEmpty() || !t.containsAll(q) || t.stream().anyMatch(REJECT::contains)) return false;
        // Ordered, contiguous query tokens prevent a bag-of-words match across an unrelated product.
        if (!String.join(" ", t).contains(String.join(" ", q))) return false;
        if (q.stream().anyMatch(token -> Collections.frequency(t, token) > 1)) return false;
        int core = 0;
        for (String token : t) {
            if (q.contains(token)) { core++; continue; }
            if (!SUFFIX.contains(token) && !token.matches("(?:64|128|256|512|1024)(?:gb)?|[124]tb")) return false;
        }
        return (double) core / t.size() >= 0.4;
    }

    /** Matches against each candidate's "brand model" AND bare model alone - either is a valid catalogue lookup. */
    public static <T> List<T> matchCatalogue(String rawName, List<T> candidates,
            java.util.function.Function<T, String> fullName, java.util.function.Function<T, String> modelOnly) {
        return candidates.stream().filter(c -> matchesCatalogueName(rawName, fullName.apply(c))
                || matchesCatalogueName(rawName, modelOnly.apply(c))).toList();
    }

    private static int extraTokenCount(String brand, String model, String title) {
        var expected = new HashSet<>(tokens(brand));
        expected.addAll(tokens(model));
        return (int) tokens(title).stream().filter(token -> !expected.contains(token)).count();
    }
}
