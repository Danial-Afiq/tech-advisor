package com.springboot.backend.ingestion;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.List;

/** Common envelope, different typed bodies. Review collection does not infer sentiment. */
public record Payload(String sourceId, String externalId, Instant observedAt, Body body) {
    public sealed interface Body permits Article, Specifications, Price, Benchmark, ReviewBatch {}
    /** One bounded product batch is one durable runner item. No raw provider objects. */
    public record ReviewBatch(long productId, List<Review> reviews) implements Body {
        public ReviewBatch { reviews = List.copyOf(reviews); }
    }
    public record Review(String fingerprint, String sourceDomain, String title, String text,
                         BigDecimal rating, String rawDate, Instant retrievedAt) {}
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
            case ReviewBatch batch -> {
                if (batch.productId() <= 0 || batch.reviews().isEmpty() || batch.reviews().size() > 100)
                    throw new IllegalArgumentException("Invalid review batch");
                for (Review r : batch.reviews()) {
                    if (r.fingerprint() == null || !r.fingerprint().matches("[a-f0-9]{64}")
                            || blank(r.text()) || r.text().length() > 8000 || blank(r.sourceDomain())
                            || r.sourceDomain().length() > 253 || r.title() == null || r.title().length() > 500
                            || r.rawDate() == null || r.rawDate().length() > 100 || r.retrievedAt() == null
                            || r.rating() == null || r.rating().compareTo(BigDecimal.ONE) < 0
                            || r.rating().compareTo(BigDecimal.valueOf(5)) > 0)
                        throw new IllegalArgumentException("Invalid review");
                }
            }
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
