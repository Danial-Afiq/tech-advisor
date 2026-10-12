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

    // UNICODE_CHARACTER_CLASS on every pattern below that uses \s: a real, live-confirmed
    // gap found via a Smartphone DB Backfill snapshot run, not hypothesized. MobileAPI's
    // "hardware"/"storage" text uses U+00A0 (non-breaking space) instead of a plain space for
    // a large share of real devices (confirmed: 19/50 devices on one real `by-year` page) -
    // e.g. "8 GB RAM, Unisoc T7510". Plain \s is ASCII-only in Java and matches nothing
    // against U+00A0, so RAM_GB/STORAGE_GB's match fails outright - not a partial/wrong match,
    // no match at all, silently returning empty for ramGb()/storageGb() AND leaving the whole
    // unstripped "8 GB RAM, Unisoc T7510" string behind as chipset(), since chipset()'s
    // "remove whatever RAM_GB matched" logic has nothing to remove. Confirmed live against a
    // real snapshot: ~50% of 6,633 pulled devices had a corrupted chipset value and a
    // null ram_gb/storage_gb from this exact cause, not from genuinely blank source text as
    // first assumed. The fix is the same one already proven for misc.price's U+2009 thin-space
    // case below - UNICODE_CHARACTER_CLASS makes \s Unicode-aware (includes U+00A0), so this
    // was always a known fix pattern for this file, just not yet applied to these.
    // Smartphone DB Backfill ticket: a real 6,670-device pull showed the "RAM word required"
    // shape above still left ~20% of chipsets contaminated, via three distinct real patterns
    // the original RAM_GB never covered: (1) multiple comma-separated RAM figures before a
    // single trailing "RAM" ("3GB, 4GB RAM, Unisoc..." - only the last one matched and got
    // removed, leaving "3GB, , Unisoc..." behind); (2) RAM given in MB, not GB ("512 MB RAM" -
    // the pattern only ever recognized the literal unit "GB"); (3) the single biggest
    // contributor - RAM stated with no "RAM" word at all ("4 GB, MediaTek Dimensity 6300
    // MT6835T"), which never matched a pattern that required that word. All three are real,
    // confirmed live, not hypothesized. "RAM" is the single field doing this job now,
    // deliberately TOKEN-shaped (one figure, not a whole-string strip) rather than anchored to
    // one position: MobileAPI's "hardware" field is RAM+chipset only, by the same convention
    // storage/battery have their own separate fields - so chipset() below strips EVERY
    // RAM_TOKEN match (replaceAll, not find-once), handling any number of RAM mentions in any
    // position, with or without the "RAM" word, with or without a separator between them.
    private static final Pattern RAM_TOKEN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(GB|MB)(?:\\s*RAM)?", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern STORAGE_GB = Pattern.compile(
            "(\\d+)\\s*GB", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern BATTERY_MAH = Pattern.compile(
            "(\\d+)\\s*mAh", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern CAMERA_MP = Pattern.compile(
            "(\\d+)\\s*MP", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern REFRESH_HZ = Pattern.compile(
            "(\\d+)\\s*Hz", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    // UNICODE_CHARACTER_CLASS: real misc.price text uses U+2009 (thin space) between the
    // currency symbol and the amount (e.g. "£ 145.00 / € 167.00 / $ 121.87") -
    // plain \s is ASCII-only in Java and silently matches nothing against that, dropping a
    // perfectly good price. Confirmed against a real captured string, not a hypothetical.
    // AMOUNT accepts both comma-grouped thousands ("1,299.00") and plain digit runs ("999") -
    // a real live misc.price string ("GET /devices/43/", iPhone 17 Pro) was silently
    // mis-parsed as "1" before this: a bare \d+ stops at the first comma. firstMatch() strips
    // any remaining comma before the BigDecimal parse.
    //
    // The first alternative requires at least one comma group ((?:,\d{3})+, not *) - with a
    // bare *, "$1299.00" matched the first alternative via \d{1,3} alone (greedy to "129"),
    // which succeeds with zero comma groups and nothing mandatory after it, so the engine
    // never backtracks to the second, fully-greedy alternative: group(1) came back "129", not
    // "1299.00". Requiring +, a string with no comma fails the first alternative outright and
    // falls through to \d+, which consumes the whole run. Caught by review, not by the
    // original test suite - none of its cases used a 4+-digit ungrouped amount.
    private static final String AMOUNT = "(\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)";
    private static final Pattern PRICE_USD = Pattern.compile(
            "\\$\\s?" + AMOUNT, Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern PRICE_EUR = Pattern.compile(
            "€\\s?" + AMOUNT, Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern PRICE_GBP = Pattern.compile(
            "£\\s?" + AMOUNT, Pattern.UNICODE_CHARACTER_CLASS);
    // INR (₹) added alongside USD/EUR/GBP - confirmed present on the same real iPhone 17 Pro
    // misc.price string, not a hypothetical currency to support.
    private static final Pattern PRICE_INR = Pattern.compile(
            "₹\\s?" + AMOUNT, Pattern.UNICODE_CHARACTER_CLASS);
    // The lookbehind stops find() from retrying at every digit inside one long number; the
    // possessive \s*+ after AMOUNT means no backtracking into the whitespace once consumed.
    // Was its own separate, comma-blind pattern until caught by review + a test: "1,299 EUR"
    // returned 299 (the digits after the comma only) because the old \d++ stopped there and
    // the lookbehind let find() retry from the first digit after the comma. Now shares AMOUNT.
    private static final Pattern PRICE_CODE = Pattern.compile(
            "(?<!\\d)" + AMOUNT + "\\s*+(USD|EUR|GBP|INR)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern CPU_GHZ = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*GHz", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern DISPLAY_INCHES = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*inches?", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern WEIGHT_G = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*g\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern QUARTER_YEAR = Pattern.compile(
            "([1-4])Q\\s*(\\d{4})", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern ANNOUNCED_MONTH_YEAR = Pattern.compile(
            "Announced\\s+([A-Za-z]+)\\s+(\\d{4})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    // Anchored at segment start (see storageOptionsGb) rather than scanning anywhere in it.
    private static final Pattern STORAGE_TOKEN = Pattern.compile(
            "^(\\d+(?:\\.\\d+)?)\\s*(GB|TB)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * Base object's "hardware" field, e.g. "Snapdragon 8 Gen 3, 8GB RAM" - also matches a
     * bare "4 GB" with no "RAM" word, and an MB-denominated figure (converted to GB, rounded
     * to the nearest whole number since ram_gb is an INTEGER column with no fractional
     * representation - a sub-512MB device rounds to 0 rather than silently returning empty
     * the way it did before RAM_TOKEN recognized MB at all).
     */
    public static Optional<BigDecimal> ramGb(String hardwareText) {
        if (hardwareText == null) return Optional.empty();
        Matcher m = RAM_TOKEN.matcher(hardwareText);
        if (!m.find()) return Optional.empty();
        try {
            BigDecimal value = new BigDecimal(m.group(1).replace(",", ""));
            if (m.group(2).equalsIgnoreCase("MB"))
                value = value.divide(new BigDecimal(1024), 0, java.math.RoundingMode.HALF_UP);
            return Optional.of(value);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Base object's "storage" field, e.g. "256GB". Takes the first (base) tier. */
    public static Optional<BigDecimal> storageGb(String storageText) { return firstMatch(storageText, STORAGE_GB); }

    /** Base object's "battery_capacity" field, e.g. "5000 mAh". */
    public static Optional<BigDecimal> batteryMah(String batteryText) { return firstMatch(batteryText, BATTERY_MAH); }

    /** Base object's "camera" field, e.g. "48 MP + 12 MP + 12 MP" — takes the main sensor (first). */
    public static Optional<BigDecimal> cameraMp(String cameraText) { return firstMatch(cameraText, CAMERA_MP); }

    /**
     * Base object's "hardware" field with every RAM mention removed, e.g.
     * "Snapdragon 8 Gen 3, 8GB RAM" -> "Snapdragon 8 Gen 3". Some real
     * devices have no chipset recorded at all (just "2 GB RAM, ") -> empty.
     *
     * RAM can lead OR trail the chipset - confirmed live (ticket 1.8, GET
     * /devices/43/): a real iPhone 17 Pro gives "12GB RAM, Apple A19 Pro",
     * RAM first - and there can be more than one RAM mention, with or
     * without a separator between them ("3GB, 4GB RAM, Unisoc...",
     * "4 GB6 GB, MediaTek..."), confirmed live against a real 6,670-device
     * pull. replaceAll (every RAM_TOKEN match, not just the first) handles
     * any count/position/spacing; comma cleanup then runs on the whole
     * string (split/trim/drop-empty/rejoin) rather than only the ends, so
     * an orphaned interior ", ," left by removing two separate mentions
     * collapses too - stripping one match and trimming only the ends (the
     * original form of this method) left exactly that kind of debris behind.
     */
    public static Optional<String> chipset(String hardwareText) {
        if (hardwareText == null) return Optional.empty();
        String stripped = RAM_TOKEN.matcher(hardwareText).replaceAll("");
        String remainder = java.util.Arrays.stream(stripped.split(","))
                .map(String::strip).filter(segment -> !segment.isEmpty())
                .collect(java.util.stream.Collectors.joining(", "));
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
        Optional<BigDecimal> inr = scan(miscResponse, PRICE_INR);
        if (inr.isPresent()) return Optional.of(new PriceMatch(inr.get(), "INR"));
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
                // Found alongside the regression fix above: a comma-grouped code-form amount
                // ("1,299 EUR") threw here (BigDecimal doesn't accept commas) and silently fell
                // through to the symbol check, which can't match a code-only string either -
                // losing the price entirely instead of mis-parsing it. firstMatch() already
                // stripped commas for every other caller; this direct construction did not.
                return Optional.of(new PriceMatch(new BigDecimal(code.group(1).replace(",", "")), code.group(2).toUpperCase(Locale.ROOT)));
            } catch (NumberFormatException ignored) { /* fall through to symbol check */ }
        }
        Optional<BigDecimal> usd = firstMatch(priceText, PRICE_USD);
        if (usd.isPresent()) return Optional.of(new PriceMatch(usd.get(), "USD"));
        Optional<BigDecimal> eur = firstMatch(priceText, PRICE_EUR);
        if (eur.isPresent()) return Optional.of(new PriceMatch(eur.get(), "EUR"));
        Optional<BigDecimal> gbp = firstMatch(priceText, PRICE_GBP);
        if (gbp.isPresent()) return Optional.of(new PriceMatch(gbp.get(), "GBP"));
        Optional<BigDecimal> inr = firstMatch(priceText, PRICE_INR);
        if (inr.isPresent()) return Optional.of(new PriceMatch(inr.get(), "INR"));
        return Optional.empty();
    }

    public record PriceMatch(BigDecimal amount, String currency) {}

    /**
     * MobileAPI's "storage" field is a free-text list of every tier the model ships in, not
     * separate records - e.g. "256GB, 512GB, 1TB" (confirmed live, GET /devices/43/, iPhone 17
     * Pro). Each comma-separated segment is matched anchored at its own start, so a garbage
     * segment like "4 GBBuy memory card" (a real captured value, a different device) still
     * yields its real leading number rather than matching nothing or matching mid-string junk.
     * "1TB" style entries are converted to GB. Order is preserved, duplicates removed.
     */
    public static java.util.List<Integer> storageOptionsGb(String storageText) {
        if (storageText == null || storageText.isBlank()) return java.util.List.of();
        var result = new java.util.LinkedHashSet<Integer>();
        for (String segment : storageText.split(",")) {
            Matcher m = STORAGE_TOKEN.matcher(segment.strip());
            if (!m.find()) continue;
            double value;
            try { value = Double.parseDouble(m.group(1)); }
            catch (NumberFormatException ignored) { continue; }
            boolean isTerabytes = m.group(2).equalsIgnoreCase("TB");
            result.add((int) Math.round(isTerabytes ? value * 1024 : value));
        }
        return java.util.List.copyOf(result);
    }

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
            // Comma thousands separators never appear in a non-price match (RAM/storage/battery
            // groups are plain \d+), so stripping unconditionally is safe and avoids a separate
            // price-only code path.
            return Optional.of(new BigDecimal(m.group(1).replace(",", "")));
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
