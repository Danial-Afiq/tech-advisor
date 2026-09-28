package com.springboot.backend.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure parsing helpers for MobileAPI.dev responses (https://mobileapi.dev/docs/).
 *
 * Base-object field names (hardware/storage/battery_capacity/camera) are
 * confirmed against a real captured response (mobileapi-response.json on
 * this branch) — not guesses anymore. Display refresh-rate/price still scan
 * defensively since those come from separate category endpoints this source
 * doesn't call (dropped for request-budget reasons — see
 * MobileApiSmartphoneSource), so they remain unverified against a real
 * response and are kept implemented/tested for when that changes.
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
    // UNICODE_CHARACTER_CLASS: real misc.price text uses U+2009 (thin space) between the
    // currency symbol and the amount (e.g. "£ 145.00 / € 167.00 / $ 121.87") -
    // plain \s is ASCII-only in Java and silently matches nothing against that, dropping a
    // perfectly good price. Confirmed against a real captured string, not a hypothetical.
    private static final Pattern PRICE_USD = Pattern.compile(
            "\\$\\s?(\\d+(?:\\.\\d{1,2})?)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern PRICE_EUR = Pattern.compile(
            "€\\s?(\\d+(?:\\.\\d{1,2})?)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern PRICE_GBP = Pattern.compile(
            "£\\s?(\\d+(?:\\.\\d{1,2})?)", Pattern.UNICODE_CHARACTER_CLASS);
    // Possessive quantifiers keep long malformed price strings linear-time: once the
    // numeric and whitespace portions are consumed, there is no useful fallback split.
    private static final Pattern PRICE_CODE = Pattern.compile(
            "(\\d++(?:\\.\\d{1,2})?)\\s*+(USD|EUR|GBP)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CPU_GHZ = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*GHz", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISPLAY_INCHES = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*inches?", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEIGHT_G = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*g\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUARTER_YEAR = Pattern.compile("([1-4])Q\\s*(\\d{4})");
    private static final Pattern ANNOUNCED_MONTH_YEAR = Pattern.compile(
            "Announced\\s+([A-Za-z]+)\\s+(\\d{4})", Pattern.CASE_INSENSITIVE);

    /** Base object's "hardware" field, e.g. "Snapdragon 8 Gen 3, 8GB RAM". */
    public static Optional<BigDecimal> ramGb(String hardwareText) { return firstMatch(hardwareText, RAM_GB); }

    /** Base object's "storage" field, e.g. "256GB". Takes the first (base) tier. */
    public static Optional<BigDecimal> storageGb(String storageText) { return firstMatch(storageText, STORAGE_GB); }

    /** Base object's "battery_capacity" field, e.g. "5000 mAh". */
    public static Optional<BigDecimal> batteryMah(String batteryText) { return firstMatch(batteryText, BATTERY_MAH); }

    /** Base object's "camera" field, e.g. "48 MP + 12 MP + 12 MP" — takes the main sensor (first). */
    public static Optional<BigDecimal> cameraMp(String cameraText) { return firstMatch(cameraText, CAMERA_MP); }

    /**
     * Base object's "hardware" field with the RAM portion removed, e.g.
     * "Snapdragon 8 Gen 3, 8GB RAM" -> "Snapdragon 8 Gen 3". Some real
     * devices have no chipset recorded at all (just "2 GB RAM, ") -> empty.
     */
    public static Optional<String> chipset(String hardwareText) {
        if (hardwareText == null) return Optional.empty();
        Matcher m = RAM_GB.matcher(hardwareText);
        String remainder = (m.find() ? hardwareText.substring(0, m.start()) : hardwareText).strip();
        while (remainder.endsWith(",")) remainder = remainder.substring(0, remainder.length() - 1).strip();
        return remainder.isEmpty() ? Optional.empty() : Optional.of(remainder);
    }

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

    /**
     * misc.price as observed live is free text like "About 100 EUR" or "" -
     * a currency CODE, not the symbol {@link #price(JsonNode)} was written
     * for (that method scans a whole node for €/$/£; this one takes the
     * plain string field directly and only needs the code form so far, but
     * checks symbol form too in case a device ever carries one).
     */
    public static Optional<PriceMatch> priceText(String priceText) {
        if (priceText == null || priceText.isBlank()) return Optional.empty();
        Matcher code = PRICE_CODE.matcher(priceText);
        if (code.find()) {
            try {
                return Optional.of(new PriceMatch(new BigDecimal(code.group(1)), code.group(2).toUpperCase(Locale.ROOT)));
            } catch (NumberFormatException ignored) { /* fall through to symbol check */ }
        }
        Optional<BigDecimal> usd = firstMatch(priceText, PRICE_USD);
        if (usd.isPresent()) return Optional.of(new PriceMatch(usd.get(), "USD"));
        Optional<BigDecimal> eur = firstMatch(priceText, PRICE_EUR);
        if (eur.isPresent()) return Optional.of(new PriceMatch(eur.get(), "EUR"));
        Optional<BigDecimal> gbp = firstMatch(priceText, PRICE_GBP);
        if (gbp.isPresent()) return Optional.of(new PriceMatch(gbp.get(), "GBP"));
        return Optional.empty();
    }

    public record PriceMatch(BigDecimal amount, String currency) {}

    /** platform.cpu, e.g. "2.0 GHz" or "Octa-core (4x1.6 GHz ... & 4x1.2 GHz ...)" - takes the first clock speed. */
    public static Optional<BigDecimal> cpuGhz(String cpuText) { return firstMatch(cpuText, CPU_GHZ); }

    /** display.size, e.g. "5.50 inches" or "6.22 inches, 96.6 cm2(~80.7% ratio)". */
    public static Optional<BigDecimal> displaySizeInches(String displaySizeText) { return firstMatch(displaySizeText, DISPLAY_INCHES); }

    /**
     * body.weight / top-level weight, e.g. "187.00 g" or "175 g (6.17 oz)".
     * Some responses give the top-level field with no unit at all
     * ("187.00") - falls back to parsing the whole trimmed string as a
     * plain number in that case rather than losing the value.
     */
    public static Optional<BigDecimal> weightGrams(String weightText) {
        Optional<BigDecimal> withUnit = firstMatch(weightText, WEIGHT_G);
        if (withUnit.isPresent()) return withUnit;
        if (weightText == null) return Optional.empty();
        try {
            return Optional.of(new BigDecimal(weightText.strip()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Best-effort, deliberately approximate: the API's own release_date
     * field is quarter-precision at best ("3Q 2019", meaning no real day
     * exists to recover), and is blank on many records where a launch date
     * only shows up inside the free-text description ("Announced May
     * 2022"). Both forms are mapped to the 1st of a representative month
     * (Q1->Jan, Q2->Apr, Q3->Jul, Q4->Oct) - this is a real precision loss,
     * not a parsing shortcut, and callers must not treat the day as exact.
     */
    public static Optional<LocalDate> releaseDate(String releaseDateText, String descriptionText) {
        Matcher quarter = QUARTER_YEAR.matcher(releaseDateText == null ? "" : releaseDateText);
        if (quarter.find()) {
            int q = Integer.parseInt(quarter.group(1));
            int year = Integer.parseInt(quarter.group(2));
            return Optional.of(LocalDate.of(year, (q - 1) * 3 + 1, 1));
        }
        Matcher announced = ANNOUNCED_MONTH_YEAR.matcher(descriptionText == null ? "" : descriptionText);
        if (announced.find()) {
            try {
                int month = parseMonth(announced.group(1));
                return Optional.of(LocalDate.of(Integer.parseInt(announced.group(2)), month, 1));
            } catch (Exception ignored) { /* unrecognized month name - leave empty */ }
        }
        return Optional.empty();
    }

    private static int parseMonth(String name) {
        for (int m = 1; m <= 12; m++) {
            String full = java.time.Month.of(m).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            String shortName = java.time.Month.of(m).getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            if (full.equalsIgnoreCase(name) || shortName.equalsIgnoreCase(name)) return m;
        }
        throw new IllegalArgumentException("Unrecognized month: " + name);
    }

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
