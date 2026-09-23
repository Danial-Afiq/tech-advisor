package com.springboot.backend.ingestion.searchapi;

import com.springboot.backend.ingestion.Payload;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;
import tools.jackson.databind.JsonNode;

public final class ReviewNormalizer {
    private ReviewNormalizer() {}
    public static String clean(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace("<<<<DATA_START>>>>", "").replace("<<<<DATA_END>>>>", "")
                .replaceAll("[\\p{Z}\\s]+", " ").trim();
    }
    private static String domain(String value) {
        try {
            String host = URI.create(value.contains("://") ? value : "https://" + value).getHost();
            if (host == null) return "";
            return host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        } catch (IllegalArgumentException e) { return ""; }
    }
    public static List<Payload.Review> normalize(long productId, Instant now, JsonNode... pages) {
        var unique = new TreeMap<String, Payload.Review>();
        for (JsonNode page : pages) for (JsonNode row : page) {
            String text = clean(row.path("text").asText(""));
            String title = clean(row.path("title").asText(""));
            String source = domain(clean(row.path("source").asText("")));
            String date = clean(row.path("date").asText(""));
            if (text.length() < 20 || text.length() > 8000 || title.length() > 500 || date.length() > 100
                    || source.isBlank() || source.length() > 253) continue;
            // Intentionally narrow: only remarks composed entirely of logistics vocabulary.
            String logistics = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ")
                    .replaceAll("\\b(the|a|and|was|is|very|my|it|with|on|time|fast|quick|great|good|excellent|delivery|shipping|seller|store|service|packaging|arrived|packed|well|prompt)\\b", "")
                    .replaceAll("\\s", "");
            if (logistics.isEmpty()) continue;
            BigDecimal rating;
            try { rating = new BigDecimal(row.path("rating").asText("")).stripTrailingZeros(); }
            catch (NumberFormatException e) { continue; }
            if (rating.compareTo(BigDecimal.ONE) < 0 || rating.compareTo(BigDecimal.valueOf(5)) > 0) continue;
            String fingerprint = fingerprint(productId, source, title, text, rating);
            unique.putIfAbsent(fingerprint, new Payload.Review(fingerprint, source, title, text, rating, date, now));
        }
        return unique.values().stream().limit(100).toList();
    }
    public static String fingerprint(long productId, String source, String title, String text, BigDecimal rating) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String part : List.of(Long.toString(productId), source, title, text, rating.stripTrailingZeros().toPlainString())) {
                byte[] bytes = clean(part).toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
                // Length-prefix each field so delimiters inside text cannot cause identity collisions.
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
