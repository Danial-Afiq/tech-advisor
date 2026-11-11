package com.springboot.backend.ingestion.simulation;

import com.springboot.backend.exception.ResourceNotFoundException;
import com.springboot.backend.marketevent.MarketEvent;
import com.springboot.backend.marketevent.MarketEventService;
import com.springboot.backend.marketevent.MarketEventType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records one current demo observation and its linked market event atomically. */
@Service
@Profile("ingestion-demo")
public class SimulatedMarketChangeService {

    private static final String SOURCE_PREFIX = "simulated-change:";
    private static final Set<String> SUPPORTED_CATEGORIES = Set.of("SMARTPHONE", "GPU");

    private final JdbcTemplate db;
    private final MarketEventService marketEvents;

    public SimulatedMarketChangeService(
            JdbcTemplate db,
            MarketEventService marketEvents,
            @Value("${spring.datasource.url}") String datasourceUrl) {

        SimulatedHistoryService.requireLocalPostgres(datasourceUrl);
        this.db = db;
        this.marketEvents = marketEvents;
    }

    @Transactional
    public Result injectPrice(PriceCommand command) {
        validate(command == null ? 0 : command.productId(), command == null ? null : command.idempotencyKey());
        if (command.newPrice() == null || command.newPrice().signum() <= 0) {
            throw new IllegalArgumentException("The new price must be positive");
        }

        ProductRow product = lockProduct(command.productId());
        String source = source("price", product.id(), command.idempotencyKey());
        List<PriceRow> existing = priceRows(product.id(), source);
        List<Long> eventIds = eventIds(product.id(), MarketEventType.PRICE_CHANGE, source);
        if (!existing.isEmpty() || !eventIds.isEmpty()) {
            if (existing.size() != 1 || eventIds.size() != 1
                    || existing.getFirst().price().compareTo(command.newPrice()) != 0) {
                throw idempotencyConflict(source);
            }
            return existingResult(product, source, eventIds.getFirst(), existing.getFirst().observedAt(),
                    MarketEventType.PRICE_CHANGE);
        }

        PriceRow old = latestPrice(product.id());
        if (old.price().compareTo(command.newPrice()) == 0) {
            throw new IllegalArgumentException("The simulated price must differ from the latest effective price");
        }
        Instant observedAt = after(old.observedAt());
        db.update(
                """
                INSERT INTO price_history(product_id, price, currency, source, observed_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                product.id(), command.newPrice(), old.currency(), source, Timestamp.from(observedAt));

        Map<String, Object> oldValue = priceValue(old.price(), old.currency(), old.observedAt());
        Map<String, Object> newValue = priceValue(command.newPrice(), old.currency(), observedAt);
        MarketEvent event = marketEvents.record(new MarketEventService.NewMarketEvent(
                product.id(),
                MarketEventType.PRICE_CHANGE,
                "Simulated price change for " + product.brand() + " " + product.modelName(),
                "Local demo-only simulated current price observation.",
                oldValue,
                newValue,
                source));
        verifyLatestPrice(product.id(), source);
        return result(product, source, event, observedAt, false);
    }

    @Transactional
    public Result injectBenchmark(BenchmarkCommand command) {
        validate(command == null ? 0 : command.productId(), command == null ? null : command.idempotencyKey());
        String benchmarkName = command == null || command.benchmarkName() == null
                ? null : command.benchmarkName().trim();
        if (benchmarkName == null || benchmarkName.isEmpty() || benchmarkName.length() > 200) {
            throw new IllegalArgumentException("A benchmark name of at most 200 characters is required");
        }
        if (command.newScore() == null || command.newScore().signum() <= 0) {
            throw new IllegalArgumentException("The new benchmark score must be positive");
        }

        ProductRow product = lockProduct(command.productId());
        String source = source("benchmark", product.id(), command.idempotencyKey());
        List<BenchmarkRow> existing = benchmarkRows(product.id(), source);
        List<Long> eventIds = eventIds(product.id(), MarketEventType.BENCHMARK_UPDATE, source);
        if (!existing.isEmpty() || !eventIds.isEmpty()) {
            if (existing.size() != 1 || eventIds.size() != 1
                    || !existing.getFirst().name().equals(benchmarkName)
                    || existing.getFirst().score().compareTo(command.newScore()) != 0) {
                throw idempotencyConflict(source);
            }
            return existingResult(product, source, eventIds.getFirst(), existing.getFirst().observedAt(),
                    MarketEventType.BENCHMARK_UPDATE);
        }

        BenchmarkRow old = latestBenchmark(product.id(), benchmarkName);
        if (old.score().compareTo(command.newScore()) == 0) {
            throw new IllegalArgumentException(
                    "The simulated benchmark score must differ from the latest effective score");
        }
        Instant observedAt = after(old.observedAt());
        db.update(
                """
                INSERT INTO benchmark_results
                    (product_id, benchmark_name, score, unit, higher_is_better, source, observed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                product.id(), old.name(), command.newScore(), old.unit(), old.higherIsBetter(), source,
                Timestamp.from(observedAt));

        Map<String, Object> oldValue = benchmarkValue(old, old.score(), old.observedAt());
        Map<String, Object> newValue = benchmarkValue(old, command.newScore(), observedAt);
        MarketEvent event = marketEvents.record(new MarketEventService.NewMarketEvent(
                product.id(),
                MarketEventType.BENCHMARK_UPDATE,
                "Simulated benchmark update for " + product.brand() + " " + product.modelName(),
                "Local demo-only simulated current benchmark observation.",
                oldValue,
                newValue,
                source));
        verifyLatestBenchmark(product.id(), old.name(), source);
        return result(product, source, event, observedAt, false);
    }

    private ProductRow lockProduct(long productId) {
        List<ProductRow> rows = db.query(
                """
                SELECT id, brand, model_name, category, status
                FROM products WHERE id = ? FOR UPDATE
                """,
                (rs, row) -> new ProductRow(
                        rs.getLong("id"), rs.getString("brand"), rs.getString("model_name"),
                        rs.getString("category"), rs.getString("status")),
                productId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("Product " + productId + " not found");
        }
        ProductRow product = rows.getFirst();
        if (!SUPPORTED_CATEGORIES.contains(product.category()) || !"VERIFIED".equals(product.status())) {
            throw new IllegalArgumentException(
                    "Simulated changes require a VERIFIED SMARTPHONE or GPU product");
        }
        return product;
    }

    private PriceRow latestPrice(long productId) {
        return db.query(
                        """
                        SELECT price, btrim(currency) AS currency, observed_at
                        FROM price_history WHERE product_id = ?
                        ORDER BY observed_at DESC, id DESC LIMIT 1
                        """,
                        (rs, row) -> new PriceRow(
                                rs.getBigDecimal("price"), rs.getString("currency"),
                                rs.getObject("observed_at", java.time.OffsetDateTime.class).toInstant()),
                        productId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Product " + productId + " has no effective price to change"));
    }

    private BenchmarkRow latestBenchmark(long productId, String benchmarkName) {
        return db.query(
                        """
                        SELECT benchmark_name, score, unit, higher_is_better, observed_at
                        FROM benchmark_results
                        WHERE product_id = ? AND benchmark_name = ?
                        ORDER BY observed_at DESC, id DESC LIMIT 1
                        """,
                        (rs, row) -> new BenchmarkRow(
                                rs.getString("benchmark_name"), rs.getBigDecimal("score"),
                                rs.getString("unit"), rs.getBoolean("higher_is_better"),
                                rs.getObject("observed_at", java.time.OffsetDateTime.class).toInstant()),
                        productId, benchmarkName)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Product " + productId + " has no " + benchmarkName + " benchmark to update"));
    }

    private List<PriceRow> priceRows(long productId, String source) {
        return db.query(
                """
                SELECT price, btrim(currency) AS currency, observed_at
                FROM price_history WHERE product_id = ? AND source = ?
                """,
                (rs, row) -> new PriceRow(
                        rs.getBigDecimal("price"), rs.getString("currency"),
                        rs.getObject("observed_at", java.time.OffsetDateTime.class).toInstant()),
                productId, source);
    }

    private List<BenchmarkRow> benchmarkRows(long productId, String source) {
        return db.query(
                """
                SELECT benchmark_name, score, unit, higher_is_better, observed_at
                FROM benchmark_results WHERE product_id = ? AND source = ?
                """,
                (rs, row) -> new BenchmarkRow(
                        rs.getString("benchmark_name"), rs.getBigDecimal("score"),
                        rs.getString("unit"), rs.getBoolean("higher_is_better"),
                        rs.getObject("observed_at", java.time.OffsetDateTime.class).toInstant()),
                productId, source);
    }

    private List<Long> eventIds(long productId, MarketEventType type, String source) {
        return db.queryForList(
                """
                SELECT id FROM market_events
                WHERE product_id = ? AND event_type = ? AND source = ?
                """,
                Long.class, productId, type.name(), source);
    }

    private Result existingResult(
            ProductRow product,
            String source,
            long eventId,
            Instant observedAt,
            MarketEventType expectedType) {

        MarketEvent event = marketEvents.find(eventId)
                .filter(found -> found.eventType() == expectedType)
                .orElseThrow(() -> idempotencyConflict(source));
        return result(product, source, event, observedAt, true);
    }

    private static Result result(
            ProductRow product,
            String source,
            MarketEvent event,
            Instant observedAt,
            boolean alreadyApplied) {

        return new Result(
                product.id(), product.category(), event.eventType(), source, event.id(),
                event.oldValue(), event.newValue(), observedAt, alreadyApplied);
    }

    private void verifyLatestPrice(long productId, String source) {
        String latest = db.queryForObject(
                """
                SELECT source FROM price_history WHERE product_id = ?
                ORDER BY observed_at DESC, id DESC LIMIT 1
                """,
                String.class, productId);
        if (!source.equals(latest)) {
            throw new IllegalStateException("Injected price did not become the latest effective observation");
        }
    }

    private void verifyLatestBenchmark(long productId, String name, String source) {
        String latest = db.queryForObject(
                """
                SELECT source FROM benchmark_results
                WHERE product_id = ? AND benchmark_name = ?
                ORDER BY observed_at DESC, id DESC LIMIT 1
                """,
                String.class, productId, name);
        if (!source.equals(latest)) {
            throw new IllegalStateException("Injected benchmark did not become the latest effective observation");
        }
    }

    private static Map<String, Object> priceValue(BigDecimal price, String currency, Instant observedAt) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("price", price);
        value.put("currency", currency);
        value.put("observedAt", observedAt.toString());
        return value;
    }

    private static Map<String, Object> benchmarkValue(BenchmarkRow baseline, BigDecimal score, Instant observedAt) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("benchmarkName", baseline.name());
        value.put("score", score);
        value.put("unit", baseline.unit());
        value.put("higherIsBetter", baseline.higherIsBetter());
        value.put("observedAt", observedAt.toString());
        return value;
    }

    private static Instant after(Instant previous) {
        Instant now = Instant.now();
        return now.isAfter(previous) ? now : previous.plusMillis(1);
    }

    private static void validate(long productId, String idempotencyKey) {
        if (productId <= 0 || idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 100
                || !idempotencyKey.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("A product and valid idempotency key are required");
        }
    }

    private static String source(String kind, long productId, String idempotencyKey) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(idempotencyKey.getBytes(StandardCharsets.UTF_8));
            return SOURCE_PREFIX + kind + ":" + productId + ":"
                    + HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static IllegalArgumentException idempotencyConflict(String source) {
        return new IllegalArgumentException("Incomplete or conflicting simulated change for source " + source);
    }

    public record PriceCommand(long productId, BigDecimal newPrice, String idempotencyKey) {}

    public record BenchmarkCommand(
            long productId,
            String benchmarkName,
            BigDecimal newScore,
            String idempotencyKey) {}

    public record Result(
            long productId,
            String category,
            MarketEventType eventType,
            String source,
            long marketEventId,
            Map<String, Object> oldValue,
            Map<String, Object> newValue,
            Instant observedAt,
            boolean alreadyApplied) {}

    private record ProductRow(long id, String brand, String modelName, String category, String status) {}
    private record PriceRow(BigDecimal price, String currency, Instant observedAt) {}
    private record BenchmarkRow(
            String name, BigDecimal score, String unit, boolean higherIsBetter, Instant observedAt) {}
}
