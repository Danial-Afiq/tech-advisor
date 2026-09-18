package com.springboot.backend.ingestion;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Map;

/** Common envelope, different typed bodies. Review collection does not infer sentiment. */
public record Payload(String sourceId, String externalId, Instant observedAt, Body body) {
    public sealed interface Body permits Article, Specifications, Price, Benchmark {}
    public record Article(String productReference, String title, URI url, Instant publishedAt,
                          String text) implements Body {}
    public record Specifications(String productReference, Map<String, BigDecimal> values,
                                 Map<String, String> units) implements Body {}
    public record Price(String productReference, BigDecimal amount, String currency) implements Body {}
    public record Benchmark(String productReference, String name, BigDecimal score, String unit)
            implements Body {}

    public void validate(String expectedSource) {
        if (!expectedSource.equals(sourceId) || externalId == null || externalId.isBlank()
                || externalId.length() > 500 || observedAt == null || body == null)
            throw new IllegalArgumentException("Invalid payload envelope");
        switch (body) {
            case Article a -> {
                if (blank(a.title()) || a.url() == null || !a.url().isAbsolute()
                        || !("https".equals(a.url().getScheme()) || "http".equals(a.url().getScheme()))
                        || blank(a.text()) || a.text().length() > 100_000)
                    throw new IllegalArgumentException("Invalid article");
            }
            case Specifications s -> {
                if (blank(s.productReference()) || s.values() == null || s.values().isEmpty()
                        || s.units() == null || !s.units().keySet().equals(s.values().keySet())
                        || s.values().values().stream().anyMatch(v -> v == null || v.signum() < 0)
                        || s.units().values().stream().anyMatch(Payload::blank))
                    throw new IllegalArgumentException("Invalid specifications");
            }
            case Price p -> {
                if (blank(p.productReference()) || p.amount() == null || p.amount().signum() < 0
                        || p.currency() == null || !p.currency().matches("[A-Z]{3}"))
                    throw new IllegalArgumentException("Invalid price");
            }
            case Benchmark b -> {
                if (blank(b.productReference()) || blank(b.name()) || b.score() == null || blank(b.unit()))
                    throw new IllegalArgumentException("Invalid benchmark");
            }
        }
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
