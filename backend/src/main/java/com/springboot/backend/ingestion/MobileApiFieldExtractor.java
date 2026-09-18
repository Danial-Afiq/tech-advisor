package com.springboot.backend.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure parsing helpers for MobileAPI.dev responses (https://mobileapi.dev/docs/).
 *
 * The public docs describe category contents in prose, not exact JSON field
 * names/shapes (e.g. "Battery: type, charging" — no literal key given). These
 * methods are deliberately defensive: they scan for recognizable number+unit
 * patterns in whatever text is present rather than assuming a fixed schema.
 * VERIFY against a real response once an API key is available, and tighten
 * these patterns (or add real field-name lookups) if the live shape differs.
 *
 * No network, no Spring — safe to unit test with fixture JSON, no database.
 */
public final class MobileApiFieldExtractor {
    private MobileApiFieldExtractor() {}

    private static final Pattern RAM_GB = Pattern.compile("(\\d+)\\s*GB\\s*RAM", Pattern.CASE_INSENSITIVE);
    private static final Pattern STORAGE_GB = Pattern.compile("(\\d+)\\s*GB", Pattern.CASE_INSENSITIVE);
    private static final Pattern BATTERY_MAH = Pattern.compile("(\\d+)\\s*mAh", Pattern.CASE_INSENSITIVE);
    private static final Pattern CAMERA_MP = Pattern.compile("(\\d+)\\s*MP", Pattern.CASE_INSENSITIVE);
    private static final Pattern REFRESH_HZ = Pattern.compile("(\\d+)\\s*Hz", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE_USD = Pattern.compile("\\$\\s?(\\d+(?:\\.\\d{1,2})?)");
    private static final Pattern PRICE_EUR = Pattern.compile("€\\s?(\\d+(?:\\.\\d{1,2})?)");
    private static final Pattern PRICE_GBP = Pattern.compile("£\\s?(\\d+(?:\\.\\d{1,2})?)");

    /** Base object's "hardware" field, e.g. "Snapdragon 8 Gen 3, 8GB RAM". */
    public static Optional<BigDecimal> ramGb(String hardwareText) { return firstMatch(hardwareText, RAM_GB); }

    /** Base object's "storage" field, e.g. "256GB". Takes the first (base) tier. */
    public static Optional<BigDecimal> storageGb(String storageText) { return firstMatch(storageText, STORAGE_GB); }

    /** Base object's "battery_capacity" field, e.g. "5000 mAh". */
    public static Optional<BigDecimal> batteryMah(String batteryText) { return firstMatch(batteryText, BATTERY_MAH); }

    /** Base object's "camera" field, e.g. "48 MP + 12 MP + 12 MP" — takes the main sensor (first). */
    public static Optional<BigDecimal> cameraMp(String cameraText) { return firstMatch(cameraText, CAMERA_MP); }

    /** Display category's exact field name is undocumented — scans every text value in the response. */
    public static Optional<BigDecimal> refreshRateHz(JsonNode displayResponse) {
        return scan(displayResponse, REFRESH_HZ);
    }

    /** Misc category price — scans for a recognizable currency amount rather than guessing a field name. */
    public static Optional<PriceMatch> price(JsonNode miscResponse) {
        Optional<BigDecimal> usd = scan(miscResponse, PRICE_USD);
        if (usd.isPresent()) return Optional.of(new PriceMatch(usd.get(), "USD"));
        Optional<BigDecimal> eur = scan(miscResponse, PRICE_EUR);
        if (eur.isPresent()) return Optional.of(new PriceMatch(eur.get(), "EUR"));
        Optional<BigDecimal> gbp = scan(miscResponse, PRICE_GBP);
        if (gbp.isPresent()) return Optional.of(new PriceMatch(gbp.get(), "GBP"));
        return Optional.empty();
    }

    public record PriceMatch(BigDecimal amount, String currency) {}

    private static Optional<BigDecimal> firstMatch(String text, Pattern pattern) {
        if (text == null) return Optional.empty();
        Matcher m = pattern.matcher(text);
        if (!m.find()) return Optional.empty();
        try {
            return Optional.of(new BigDecimal(m.group(1)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<BigDecimal> scan(JsonNode node, Pattern pattern) {
        if (node == null) return Optional.empty();
        if (node.isTextual()) return firstMatch(node.asText(), pattern);
        if (node.isObject() || node.isArray()) {
            for (JsonNode child : node) {
                Optional<BigDecimal> found = scan(child, pattern);
                if (found.isPresent()) return found;
            }
        }
        return Optional.empty();
    }
}
