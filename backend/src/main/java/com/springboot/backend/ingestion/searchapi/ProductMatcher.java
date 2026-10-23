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
                throw new IllegalArgumentException("Enter both the product brand and model");
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
    /**
     * Ticket 1.3. GPU listings are partner boards ("MSI GeForce RTX 4070 Ventus 2X 12G OC"), so
     * the board maker and its line words are allowed suffixes, the chip vendor need not appear,
     * and the longer titles get a lower core-token share. Tier words (Ti, Super, XT, XTX, GRE)
     * and four-digit numbers are never suffixes, so a 4070 never matches a 4070 Ti or a 4060.
     * Memory sizes are split from their unit ("12GB", "12G", "12 GB" all read as 12 gb), and a
     * memory size in the catalogue name ("RTX 3060 12GB") must appear in the title but may sit
     * anywhere in it, since boards put it after their line name.
     */
    private record Rules(Set<String> reject, Set<String> suffix, String extra, boolean brandRequired,
                         double minCoreShare, boolean splitMemory) {}
    private static final Rules PHONE = new Rules(REJECT, SUFFIX, "(?:64|128|256|512|1024)(?:gb)?|[124]tb",
            true, 0.4, false);
    private static final Set<String> GPU_REJECT = union(REJECT, Set.of("laptop", "notebook", "riser",
            "waterblock", "backplate", "bracket", "prebuilt"));
    private static final Set<String> GPU_SUFFIX = Set.of("nvidia", "amd", "intel",
            "asus", "rog", "strix", "tuf", "dual", "prime", "proart", "msi", "ventus", "gaming", "trio", "slim",
            "inspire", "shadow", "suprim", "expert", "gigabyte", "aorus", "eagle", "windforce", "aero", "elite",
            "master", "zotac", "twin", "edge", "amp", "trinity", "airo", "solid", "pny", "verto", "xlr8", "epic",
            "palit", "jetstream", "infinity", "stormx", "gamerock", "gainward", "ghost", "phantom", "python",
            "inno3d", "ichill", "colorful", "igame", "ultra", "vulcan", "galax", "kfa2", "evga", "ftw3", "xc",
            "sapphire", "pulse", "nitro", "pure", "powercolor", "red", "devil", "dragon", "hellhound", "fighter",
            "reaper", "xfx", "speedster", "swft", "merc", "qick", "asrock", "challenger", "steel", "legend",
            "taichi", "sparkle", "titan", "roc", "acer", "predator", "bifrost", "nova", "maxsun", "yeston",
            "oc", "edition", "founders", "limited", "graphics", "card", "video", "gpu", "pci", "pcie", "express",
            "e", "hdmi", "dp", "displayport", "gddr5", "gddr5x", "gddr6", "gddr6x", "gddr7", "gb", "bit", "x",
            "fan", "fans", "triple", "white", "black", "lhr", "boost", "overclocked", "low", "profile", "lp",
            "mini", "itx", "compact", "new", "with");
    private static final Rules GPU = new Rules(GPU_REJECT, GPU_SUFFIX,
            "\\d{1,3}|[1-4]x|x\\d{1,2}|v\\d", false, 0.2, true);
    private static final java.util.regex.Pattern MEMORY = java.util.regex.Pattern.compile("(\\d{1,3})gb?");
    private static Rules rules(String category) { return "GPU".equals(category) ? GPU : PHONE; }
    private static Set<String> union(Set<String> a, Set<String> b) {
        var all = new HashSet<>(a); all.addAll(b); return Set.copyOf(all);
    }
    /** For GPUs, "12GB" and "12G" become "12", "gb", so every spelling of a memory size reads the same. */
    private static List<String> tokens(String value, Rules rules) {
        var plain = tokens(value);
        if (!rules.splitMemory()) return plain;
        var split = new ArrayList<String>();
        for (String token : plain) {
            var memory = MEMORY.matcher(token);
            if (memory.matches()) { split.add(memory.group(1)); split.add("gb"); }
            else split.add(token);
        }
        return split;
    }
    private static boolean containsRun(List<String> haystack, List<String> run) {
        return run.isEmpty() || (" " + String.join(" ", haystack) + " ").contains(" " + String.join(" ", run) + " ");
    }
    static List<String> tokens(String value) {
        return Arrays.stream(Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replace("+", " plus ").replaceAll("[^\\p{L}\\p{N}]+", " ").trim().split(" +"))
                .filter(s -> !s.isBlank()).toList();
    }
    public static boolean accepts(String brand, String model, String title) {
        return accepts("SMARTPHONE", brand, model, title);
    }
    public static boolean accepts(String category, String brand, String model, String title) {
        Rules rules = rules(category);
        List<String> b = tokens(brand, rules), m = tokens(model, rules), t = tokens(title, rules);
        if (b.isEmpty() || m.isEmpty() || (rules.brandRequired() && !t.containsAll(b)) || !t.containsAll(m)
                || t.stream().anyMatch(rules.reject()::contains)) return false;
        // A trailing memory size may sit anywhere in a GPU title; the rest of the model may not.
        List<String> memory = rules.splitMemory() && m.size() > 2 && m.getLast().equals("gb")
                && m.get(m.size() - 2).matches("\\d+") ? m.subList(m.size() - 2, m.size()) : List.of();
        List<String> name = m.subList(0, m.size() - memory.size());
        // Ordered, contiguous model tokens prevent mixed-model titles matching a bag of words.
        if (rules.splitMemory() ? !containsRun(t, name) || !containsRun(t, memory)
                : !String.join(" ", t).contains(String.join(" ", m))) return false;
        var expected = new HashSet<>(b); expected.addAll(name);
        if (expected.stream().anyMatch(token -> Collections.frequency(t, token) > 1)) return false;
        expected.addAll(memory);
        int core = 0;
        for (String token : t) {
            if (expected.contains(token)) { core++; continue; }
            if (!rules.suffix().contains(token) && !token.matches(rules.extra())) return false;
        }
        return (double) core / t.size() >= rules.minCoreShare();
    }
    public static Match choose(String brand, String model, JsonNode results) {
        return choose("SMARTPHONE", brand, model, results);
    }
    /**
     * For a GPU, equally specific partner boards are all listings of the same chip, so the
     * first in Google's order wins instead of failing as ambiguous; reviews attach to the
     * reference product either way. Phones keep failing closed.
     */
    public static Match choose(String category, String brand, String model, JsonNode results) {
        var matches = validMatches(category, brand, model, results);
        if (matches.isEmpty()) throw new IngestionFailure(SEARCHAPI_NO_MATCH);
        int bestScore = matches.values().stream().mapToInt(
                match -> extraTokenCount(brand, model, match.title())).min().orElseThrow();
        var best = matches.values().stream().filter(
                match -> extraTokenCount(brand, model, match.title()) == bestScore).toList();
        if (best.size() != 1 && rules(category) != GPU) throw new IngestionFailure(SEARCHAPI_AMBIGUOUS_MATCH);
        return best.getFirst();
    }

    public static Match choose(String brand, String model, JsonNode results, String externalProductId) {
        return choose("SMARTPHONE", brand, model, results, externalProductId);
    }
    public static Match choose(String category, String brand, String model, JsonNode results,
                               String externalProductId) {
        Match selected = validMatches(category, brand, model, results).get(externalProductId);
        if (selected == null) throw new IngestionFailure(SEARCHAPI_NO_MATCH);
        return selected;
    }

    public static List<Candidate> candidates(String brand, String model, JsonNode results) {
        return candidates("SMARTPHONE", brand, model, results);
    }
    public static List<Candidate> candidates(String category, String brand, String model, JsonNode results) {
        return validMatches(category, brand, model, results).values().stream().limit(20)
                .map(match -> new Candidate(match.externalId(), match.title())).toList();
    }

    private static Map<String, Match> validMatches(String category, String brand, String model,
                                                   JsonNode results) {
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
                    && accepts(category, brand, model, title)) {
                Match match = new Match(id, token, title, extractedPrice(row), currencyOf(row));
                matches.merge(id, match, (a, z) -> specificity.compare(a, z) <= 0 ? a : z);
            }
        }
        return matches;
    }

    /**
     * Finds storage capacities in a shopping title with a single left-to-right scan.
     * A regex here was flagged by SonarCloud for super-linear backtracking; scanning
     * digits, optional decimals, whitespace, and GB/TB units directly is linear.
     */
    static List<Integer> storageGbMentionsInTitle(String title) {
        if (title == null) return List.of();
        var found = new LinkedHashSet<Integer>();
        int index = 0;
        while (index < title.length()) {
            if (!asciiDigit(title.charAt(index))) {
                index++;
                continue;
            }
            int start = index;
            while (index < title.length() && asciiDigit(title.charAt(index))) index++;
            if (index + 1 < title.length() && title.charAt(index) == '.'
                    && asciiDigit(title.charAt(index + 1))) {
                index++;
                while (index < title.length() && asciiDigit(title.charAt(index))) index++;
            }
            int end = index;
            while (index < title.length() && regexWhitespace(title.charAt(index))) index++;
            if (index + 1 < title.length()
                    && (title.charAt(index) == 'G' || title.charAt(index) == 'g'
                        || title.charAt(index) == 'T' || title.charAt(index) == 't')
                    && (title.charAt(index + 1) == 'B' || title.charAt(index + 1) == 'b')) {
                double value = Double.parseDouble(title.substring(start, end));
                if (title.charAt(index) == 'T' || title.charAt(index) == 't') value *= 1024;
                if (Double.isFinite(value) && value <= Integer.MAX_VALUE)
                    found.add((int) Math.round(value));
                index += 2;
            } else {
                index = end;
            }
        }
        return List.copyOf(found);
    }

    private static boolean asciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean regexWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r'
                || c == '\f' || c == 0x0B;
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
