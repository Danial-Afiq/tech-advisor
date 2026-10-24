package com.springboot.backend.ingestion.gpu;

import com.springboot.backend.ingestion.core.Payload;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Pure HTML-to-record parsing for TechSpot's GPU spec database, kept separate from fetching so
 * it can be tested against saved pages without the network. parseList is also the safety
 * boundary for crawling: it only ever hands back https://www.techspot.com/specs/gpu/... detail
 * URLs. parseDetail never throws on a malformed value; it leaves that field null and lets
 * GpuSpecValidator decide whether the card is still usable.
 */
public final class TechSpotGpuParser {
    public static final String HOST = "www.techspot.com";
    private static final Pattern DETAIL_PATH = Pattern.compile("^/specs/gpu/\\d+-[a-z0-9-]+\\.html$");
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([A-Za-z]*)");
    private static final Pattern INTEGER = Pattern.compile("\\d+");
    private static final Pattern LEADING_INT = Pattern.compile("^(\\d{1,9})");
    private static final Pattern DOLLARS = Pattern.compile("^\\$\\s*(\\d[\\d,]*(?:\\.\\d+)?)");
    private static final DateTimeFormatter RELEASE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    public record Listing(URI url, String name) {}
    public record ListPage(List<Listing> listings, int lastPage) {}

    private TechSpotGpuParser() {}

    public static ListPage parseList(String html, URI pageUrl) {
        Document doc = Jsoup.parse(html, pageUrl.toString());
        Map<URI, Listing> byUrl = new LinkedHashMap<>();
        for (Element a : doc.select(".ProdListInfo h4 a[href]")) {
            URI url = detailUrl(a.absUrl("href"));
            if (url != null) byUrl.putIfAbsent(url, new Listing(url, a.text().trim()));
        }
        int lastPage = 1;
        for (Element a : doc.select("li.pagenum a")) {
            Matcher m = INTEGER.matcher(a.text());
            while (m.find()) {
                try { lastPage = Math.max(lastPage, Integer.parseInt(m.group())); }
                catch (NumberFormatException ignored) { /* absurdly long digit run */ }
            }
        }
        return new ListPage(List.copyOf(byUrl.values()), lastPage);
    }

    /** Empty when the page is not a Desktop card or has no name heading. */
    public static Optional<Payload.GpuSpec> parseDetail(String html, URI url) {
        Document doc = Jsoup.parse(html, url == null ? "" : url.toString());
        Element h1 = doc.selectFirst("h1[itemprop=name]");
        String name = h1 == null ? "" : h1.text().trim();
        if (name.isEmpty()) return Optional.empty();
        Map<String, String> specs = specTable(doc);
        if (!"Desktop".equalsIgnoreCase(specs.get("Type"))) return Optional.empty();

        String firstWord = name.split("\\s+", 2)[0];
        String brand = GpuNames.brand(firstWord);
        String model = brand == null ? name : GpuNames.model(brand, name);
        String tdp = specs.getOrDefault("Total Board Power", specs.get("TDP"));
        return Optional.of(new Payload.GpuSpec(
                brand == null ? firstWord : brand, model, date(specs.get("Release date")),
                mhz(specs.get("Base Clock")), mhz(specs.get("Boost Clock")), gb(specs.get("VRAM Capacity")),
                text(specs.get("Memory Type")), leadingInt(specs.get("Memory Bus")), leadingInt(tdp),
                usd(specs.get("Price at Launch")), text(specs.get("Architecture")), url));
    }

    private static URI detailUrl(String abs) {
        if (abs == null || abs.isEmpty()) return null;
        try {
            URI u = new URI(abs);
            boolean ok = "https".equals(u.getScheme()) && HOST.equals(u.getHost()) && u.getPort() == -1
                    && u.getRawUserInfo() == null && u.getRawQuery() == null && u.getRawFragment() == null
                    && u.getPath() != null && DETAIL_PATH.matcher(u.getPath()).matches();
            return ok ? u : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Labels repeat across sections (VRAM Capacity, Total Board Power); the first one wins. */
    private static Map<String, String> specTable(Document doc) {
        Map<String, String> specs = new HashMap<>();
        for (Element label : doc.select("td.tright")) {
            Element value = label.nextElementSibling();
            if (value == null || !value.hasClass("tleft")) continue;
            String key = label.text().trim();
            if (key.endsWith(":")) key = key.substring(0, key.length() - 1).trim();
            if (!key.isEmpty()) specs.putIfAbsent(key, value.text().trim());
        }
        return specs;
    }

    private static LocalDate date(String v) {
        if (v == null) return null;
        try { return LocalDate.parse(v, RELEASE); }
        catch (DateTimeParseException e) { return null; }
    }

    /** "1.92 GHz" -> 1920, "1500 MHz" -> 1500. */
    private static Integer mhz(String v) {
        Matcher m = number(v);
        if (m == null) return null;
        BigDecimal n = new BigDecimal(m.group(1));
        try {
            return switch (m.group(2).toLowerCase(Locale.ROOT)) {
                case "ghz" -> n.movePointRight(3).setScale(0, RoundingMode.HALF_UP).intValueExact();
                case "mhz" -> n.setScale(0, RoundingMode.HALF_UP).intValueExact();
                default -> null;
            };
        } catch (ArithmeticException e) {
            return null;
        }
    }

    /** "12 GB" -> 12, "512 MB" -> 0.5. */
    private static BigDecimal gb(String v) {
        Matcher m = number(v);
        if (m == null) return null;
        BigDecimal n = new BigDecimal(m.group(1));
        return switch (m.group(2).toLowerCase(Locale.ROOT)) {
            case "gb" -> n.stripTrailingZeros();
            case "mb" -> n.divide(BigDecimal.valueOf(1024), 3, RoundingMode.HALF_UP).stripTrailingZeros();
            default -> null;
        };
    }

    /** "192-bit" -> 192, "200 W" -> 200. */
    private static Integer leadingInt(String v) {
        if (v == null) return null;
        Matcher m = LEADING_INT.matcher(v.trim());
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /** Only a "$" figure is a price; "N/A", "Unknown" and the like mean not published. */
    private static BigDecimal usd(String v) {
        if (v == null || !v.trim().startsWith("$")) return null;
        Matcher m = DOLLARS.matcher(v.trim());
        if (!m.find()) return null;
        try { return new BigDecimal(m.group(1).replace(",", "")); }
        catch (NumberFormatException e) { return null; }
    }

    private static Matcher number(String v) {
        if (v == null) return null;
        Matcher m = NUMBER.matcher(v.trim());
        if (!m.lookingAt()) return null;
        try { new BigDecimal(m.group(1)); return m; }
        catch (NumberFormatException e) { return null; }
    }

    private static String text(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
