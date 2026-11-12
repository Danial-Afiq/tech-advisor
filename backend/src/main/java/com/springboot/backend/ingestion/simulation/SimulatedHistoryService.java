package com.springboot.backend.ingestion.simulation;

import com.springboot.backend.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists repeatable demo history without changing genuine observations. */
@Service
@Profile("ingestion-demo")
public class SimulatedHistoryService {

    static final String SOURCE_PREFIX = "simulated-history:";
    private static final Set<String> SUPPORTED_CATEGORIES = Set.of("SMARTPHONE", "GPU");
    private static final Set<String> LOCAL_HOSTS = Set.of(
            "localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1");

    private final JdbcTemplate db;
    private final SimulatedHistoryGenerator generator;

    public SimulatedHistoryService(
            JdbcTemplate db,
            SimulatedHistoryGenerator generator,
            @Value("${spring.datasource.url}") String datasourceUrl) {

        requireLocalPostgres(datasourceUrl);
        this.db = db;
        this.generator = generator;
    }

    /**
     * Generates and inserts one complete scenario in a single transaction.
     * No market event is emitted: historical backfill must not trigger recommendations.
     */
    @Transactional
    public Result generate(Command command) {
        validate(command);
        ProductRow product = product(command.productId());
        String source = scenarioSource(command);

        // No schema change is needed for idempotency. Serialise identical scenario
        // calls, then use the source label as their durable identity.
        db.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))::text",
                String.class,
                source);

        int existingPrices = count("price_history", command.productId(), source);
        int existingBenchmarks = count("benchmark_results", command.productId(), source);
        Instant lastAt = command.startAt().plus(command.interval().multipliedBy(command.observationCount() - 1L));
        if (existingPrices > 0 || existingBenchmarks > 0) {
            boolean pricesComplete = existingPrices == 0 || existingPrices == command.observationCount();
            boolean benchmarksComplete = existingBenchmarks == 0
                    || existingBenchmarks % command.observationCount() == 0;
            if (!pricesComplete || !benchmarksComplete) {
                throw new IllegalStateException(
                        "Existing simulated history is incomplete for source " + source);
            }
            return new Result(
                    product.id(), product.category(), source,
                    0, 0, existingPrices, existingBenchmarks,
                    command.startAt(), lastAt, true);
        }

        Optional<PriceBaseline> price = genuinePriceBaseline(product.id());
        List<BenchmarkBaselineRow> benchmarks = genuineBenchmarkBaselines(product.id());
        if (price.isEmpty() && benchmarks.isEmpty()) {
            throw new IllegalArgumentException(
                    "Product " + product.id() + " needs a genuine price or benchmark observation");
        }
        ensureHistoryStaysHistorical(product.id(), lastAt, price, benchmarks);

        var generated = generator.generate(
                price.map(PriceBaseline::price).orElse(null),
                benchmarks.stream()
                        .map(row -> new SimulatedHistoryGenerator.BenchmarkBaseline(
                                row.name(), row.score(), row.unit(), row.higherIsBetter()))
                        .toList(),
                new SimulatedHistoryGenerator.Config(
                        command.startAt(), command.observationCount(), command.interval(),
                        command.seed(), command.priceVariation(), command.benchmarkVariation()));

        int insertedPrices = 0;
        int insertedBenchmarks = 0;
        for (var observation : generated.observations()) {
            if (observation.price() != null) {
                insertedPrices += db.update(
                        """
                        INSERT INTO price_history (product_id, price, currency, source, observed_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                        product.id(), observation.price(), price.orElseThrow().currency(), source,
                        Timestamp.from(observation.observedAt()));
            }

            for (var benchmark : observation.benchmarks()) {
                insertedBenchmarks += db.update(
                        """
                        INSERT INTO benchmark_results
                            (product_id, benchmark_name, score, unit, higher_is_better, source, observed_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                        product.id(), benchmark.name(), benchmark.score(), benchmark.unit(),
                        benchmark.higherIsBetter(), source, Timestamp.from(observation.observedAt()));
            }
        }

        return new Result(
                product.id(), product.category(), source,
                insertedPrices, insertedBenchmarks, 0, 0,
                command.startAt(), lastAt, false);
    }

    private ProductRow product(long productId) {
        List<ProductRow> rows = db.query(
                "SELECT id, category, status FROM products WHERE id = ?",
                (rs, row) -> new ProductRow(
                        rs.getLong("id"), rs.getString("category"), rs.getString("status")),
                productId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("Product " + productId + " not found");
        }
        ProductRow product = rows.getFirst();
        if (!SUPPORTED_CATEGORIES.contains(product.category())) {
            throw new IllegalArgumentException("Simulated history supports SMARTPHONE and GPU products only");
        }
        if (!"VERIFIED".equals(product.status())) {
            throw new IllegalArgumentException("Simulated history requires a VERIFIED product");
        }
        return product;
    }

    private Optional<PriceBaseline> genuinePriceBaseline(long productId) {
        return db.query(
                        """
                        SELECT price, btrim(currency) AS currency, observed_at
                        FROM price_history
                        WHERE product_id = ?
                          AND (source IS NULL OR source NOT LIKE 'simulated-%')
                        ORDER BY observed_at DESC, id DESC
                        LIMIT 1
                        """,
                        (rs, row) -> new PriceBaseline(
                                rs.getBigDecimal("price"),
                                rs.getString("currency"),
                                rs.getObject("observed_at", java.time.OffsetDateTime.class).toInstant()),
                        productId)
                .stream()
                .findFirst();
    }

    private List<BenchmarkBaselineRow> genuineBenchmarkBaselines(long productId) {
        List<BenchmarkBaselineRow> rows = db.query(
                """
                SELECT DISTINCT ON (benchmark_name)
                       benchmark_name, score, unit, higher_is_better, observed_at
                FROM benchmark_results
                WHERE product_id = ?
                  AND (source IS NULL OR source NOT LIKE 'simulated-%')
                ORDER BY benchmark_name, observed_at DESC, id DESC
                """,
                (rs, row) -> new BenchmarkBaselineRow(
                        rs.getString("benchmark_name"),
                        rs.getBigDecimal("score"),
                        rs.getString("unit"),
                        rs.getBoolean("higher_is_better"),
                        rs.getObject("observed_at", java.time.OffsetDateTime.class).toInstant()),
                productId);
        return rows;
    }

    private void ensureHistoryStaysHistorical(
            long productId,
            Instant generatedLastAt,
            Optional<PriceBaseline> price,
            List<BenchmarkBaselineRow> baselines) {

        if (price.isPresent()) {
            Instant effectivePriceAt = db.queryForObject(
                    """
                    SELECT observed_at FROM price_history
                    WHERE product_id = ?
                    ORDER BY observed_at DESC, id DESC LIMIT 1
                    """,
                    (rs, row) -> rs.getObject(1, java.time.OffsetDateTime.class).toInstant(),
                    productId);
            if (effectivePriceAt == null || !generatedLastAt.isBefore(effectivePriceAt)) {
                throw new IllegalArgumentException(
                        "Generated price history must end before the current latest price observation");
            }
        }

        Map<String, Instant> latestByName = new LinkedHashMap<>();
        db.query(
                """
                SELECT DISTINCT ON (benchmark_name) benchmark_name, observed_at
                FROM benchmark_results
                WHERE product_id = ?
                ORDER BY benchmark_name, observed_at DESC, id DESC
                """,
                (rs, row) -> Map.entry(
                        rs.getString("benchmark_name"),
                        rs.getObject("observed_at", java.time.OffsetDateTime.class).toInstant()),
                productId).forEach(entry -> latestByName.put(entry.getKey(), entry.getValue()));

        for (BenchmarkBaselineRow baseline : baselines) {
            Instant latest = latestByName.get(baseline.name());
            if (latest == null || !generatedLastAt.isBefore(latest)) {
                throw new IllegalArgumentException(
                        "Generated benchmark history must end before the current latest "
                                + baseline.name() + " observation");
            }
        }

        // Keep the explicit baselines load-bearing and visible to static analysis.
        if (price.isPresent() && price.get().observedAt() == null) {
            throw new IllegalStateException("Baseline observations must have timestamps");
        }
        if (baselines.stream().anyMatch(b -> b.observedAt() == null)) {
            throw new IllegalStateException("Baseline observations must have timestamps");
        }
    }

    private int count(String table, long productId, String source) {
        // table is selected only from the two fixed literals above, never request text.
        Integer count = db.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE product_id = ? AND source = ?",
                Integer.class,
                productId,
                source);
        return count == null ? 0 : count;
    }

    private static String scenarioSource(Command command) {
        String canonical = command.productId() + "|" + command.startAt() + "|"
                + command.observationCount() + "|" + command.interval() + "|"
                + command.seed() + "|" + normalized(command.priceVariation()) + "|"
                + normalized(command.benchmarkVariation());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return SOURCE_PREFIX + command.productId() + ":"
                    + HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String normalized(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static void validate(Command command) {
        if (command == null || command.productId() <= 0 || command.startAt() == null
                || command.interval() == null || command.interval().isZero()
                || command.interval().isNegative()
                || command.interval().compareTo(Duration.ofDays(365)) > 0
                || command.observationCount() < SimulatedHistoryGenerator.MIN_OBSERVATIONS
                || command.observationCount() > SimulatedHistoryGenerator.MAX_OBSERVATIONS
                || command.priceVariation() == null
                || command.priceVariation().signum() < 0
                || command.priceVariation().compareTo(SimulatedHistoryGenerator.MAX_PRICE_VARIATION) > 0
                || command.benchmarkVariation() == null
                || command.benchmarkVariation().signum() < 0
                || command.benchmarkVariation().compareTo(
                        SimulatedHistoryGenerator.MAX_BENCHMARK_VARIATION) > 0) {
            throw new IllegalArgumentException("Simulation history request is invalid");
        }
    }

    /** Rejects every non-loopback JDBC host, regardless of database name. */
    static void requireLocalPostgres(String datasourceUrl) {
        if (datasourceUrl == null || !datasourceUrl.startsWith("jdbc:postgresql://")) {
            throw new IllegalStateException("Simulated history requires a local PostgreSQL JDBC URL");
        }
        try {
            URI uri = URI.create(datasourceUrl.substring("jdbc:".length()));
            String host = uri.getHost();
            if (host == null) {
                throw new IllegalStateException("Simulated history requires an explicit loopback database host");
            }
            String normalized = host.toLowerCase(java.util.Locale.ROOT);
            if (normalized.startsWith("[") && normalized.endsWith("]")) {
                normalized = normalized.substring(1, normalized.length() - 1);
            }
            if (!LOCAL_HOSTS.contains(normalized)) {
                throw new IllegalStateException(
                        "Simulated history is disabled for remote database host " + host);
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Simulated history requires a valid local PostgreSQL JDBC URL", invalid);
        }
    }

    public record Command(
            long productId,
            Instant startAt,
            int observationCount,
            Duration interval,
            long seed,
            BigDecimal priceVariation,
            BigDecimal benchmarkVariation) {}

    public record Result(
            long productId,
            String category,
            String source,
            int priceRowsInserted,
            int benchmarkRowsInserted,
            int existingPriceRows,
            int existingBenchmarkRows,
            Instant firstObservedAt,
            Instant lastObservedAt,
            boolean alreadyGenerated) {}

    private record ProductRow(long id, String category, String status) {}
    private record PriceBaseline(BigDecimal price, String currency, Instant observedAt) {}
    private record BenchmarkBaselineRow(
            String name, BigDecimal score, String unit, boolean higherIsBetter, Instant observedAt) {}
}
