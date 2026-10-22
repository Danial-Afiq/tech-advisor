package com.springboot.backend.ingestion.techapi;

import static org.junit.jupiter.api.Assertions.*;

import com.springboot.backend.ingestion.core.IngestionSink;
import com.springboot.backend.ingestion.core.IngestionSource;
import com.springboot.backend.ingestion.core.Payload;
import com.springboot.backend.ingestion.core.SourceContext;
import com.springboot.backend.model.Phone;
import com.springboot.backend.recommendation.classification.SpecComparisonService;
import com.springboot.backend.recommendation.classification.SpecFactorCatalog;
import com.springboot.backend.repository.BenchmarkResultRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "ingestion.reconciliation-enabled=false")
class TechApiBenchmarkSinkTests {
    private static final String PROVENANCE =
            "TechAPI GitHub main data/soc/qualcomm/2024/chip.json; representative processor-level score; CC BY-SA 4.0";
    private static final IngestionSource SOURCE = new IngestionSource() {
        @Override public String sourceId() { return TechApiBenchmarkSource.ID; }
        @Override public void ingest(SourceContext context, java.util.function.Consumer<Payload> output) {}
    };

    @Autowired TechApiBenchmarkSink sink;
    @Autowired BenchmarkResultRepository benchmarks;
    @Autowired JdbcTemplate db;
    @Autowired SpecComparisonService comparisons;
    @Autowired TechApiBenchmarkRepository techApiPhones;
    long ownedId;
    long candidateId;

    @BeforeEach
    void reset() {
        assertTrue(db.queryForObject("SELECT current_database()", String.class).endsWith("_test"),
                "Integration tests require a dedicated database whose name ends in _test");
        db.update("DELETE FROM products WHERE brand='TechApiSinkTest'");
        db.update("DELETE FROM system_log WHERE component='TECHAPI_BENCHMARK_STATE'");
        ownedId = product("Owned");
        candidateId = product("Candidate");
    }

    @Test
    void storesProvenanceAndNeverRefreshesAnExistingTechApiMetric() {
        assertTrue(sink.supports(SOURCE, benchmark(ownedId, "single", "1000").body()));
        assertEquals(IngestionSink.Result.ACCEPTED,
                sink.accept("run-1", benchmark(ownedId, "single", "1000")));
        assertEquals(IngestionSink.Result.DUPLICATE,
                sink.accept("run-2", benchmark(ownedId, "single", "1000")));
        assertEquals(IngestionSink.Result.DUPLICATE,
                sink.accept("run-3", benchmark(ownedId, "single", "1200",
                        Instant.parse("2026-10-20T00:00:00Z"))));

        assertEquals(1, db.queryForObject(
                "SELECT count(*) FROM benchmark_results WHERE product_id=?", Integer.class, ownedId));
        var latest = benchmarks.findLatestPerBenchmark(ownedId).getFirst();
        assertEquals(0, new BigDecimal("1000").compareTo(latest.getScore()));
        assertEquals(PROVENANCE, latest.getSource());
        assertEquals("points", latest.getUnit());
        assertTrue(latest.isHigherIsBetter());
    }

    @Test
    void ingestedValuesFlowThroughTheExistingComparisonAlgorithm() {
        sink.accept("run-1", benchmark(ownedId, TechApiBenchmarkSource.GEEKBENCH_SINGLE, "1000"));
        sink.accept("run-1", benchmark(ownedId, TechApiBenchmarkSource.GEEKBENCH_MULTI, "2000"));
        sink.accept("run-1", benchmark(candidateId, TechApiBenchmarkSource.GEEKBENCH_SINGLE, "1500"));
        sink.accept("run-1", benchmark(candidateId, TechApiBenchmarkSource.GEEKBENCH_MULTI, "3000"));

        var result = comparisons.compare(
                Phone.builder(ownedId).build(), Map.of(), Phone.builder(candidateId).build(),
                benchmarks.findLatestPerBenchmark(ownedId),
                benchmarks.findLatestPerBenchmark(candidateId), null, null, "SGD");

        assertEquals(50.0, result.benchmarkUpliftPct());
        assertTrue(result.scored().stream().anyMatch(
                spec -> spec.spec().equals(SpecFactorCatalog.BENCHMARK_SPEC)));
    }

    @Test
    void durableStateAndCatalogueCursorResumeAtTheNextEligiblePhone() {
        var state = new TechApiIngestionState();
        state.snapshotRevision = "a".repeat(40);
        state.cursor = "qualcomm/2024/first.json";
        state.completedUnits = 12;
        state.catalogueCursor = ownedId - 1;
        techApiPhones.save(state);

        var restored = techApiPhones.state();
        assertEquals(state.snapshotRevision, restored.snapshotRevision);
        assertEquals(state.cursor, restored.cursor);
        assertEquals(12, restored.completedUnits);

        var first = techApiPhones.nextPhones(restored.catalogueCursor, 1);
        assertEquals(ownedId, first.phones().getFirst().productId());
        assertTrue(first.hasMore());

        restored.catalogueCursor = ownedId;
        techApiPhones.save(restored);
        var resumed = techApiPhones.nextPhones(techApiPhones.state().catalogueCursor, 1);
        assertEquals(candidateId, resumed.phones().getFirst().productId());
    }

    @Test
    void repositoryReportsCatalogueAndBenchmarkCoverage() {
        assertFalse(techApiPhones.state().initialBackfillComplete);
        assertTrue(techApiPhones.maximumEligibleProductId() >= candidateId);
        assertTrue(techApiPhones.phonesThrough(ownedId).stream()
                .anyMatch(phone -> phone.productId() == ownedId));
        assertTrue(techApiPhones.allPhones().stream()
                .anyMatch(phone -> phone.productId() == candidateId));

        long observationsBefore = techApiPhones.observationCount();
        long productsBefore = techApiPhones.coveredProductCount();
        assertFalse(techApiPhones.hasCompleteCoverage(ownedId));

        sink.accept("run-1", benchmark(ownedId, TechApiBenchmarkSource.GEEKBENCH_SINGLE, "1000"));
        assertFalse(techApiPhones.hasCompleteCoverage(ownedId));
        sink.accept("run-1", benchmark(ownedId, TechApiBenchmarkSource.GEEKBENCH_MULTI, "2000"));

        assertTrue(techApiPhones.hasCompleteCoverage(ownedId));
        assertEquals(observationsBefore + 2, techApiPhones.observationCount());
        assertEquals(productsBefore + 1, techApiPhones.coveredProductCount());
    }

    @Test
    void sinkRejectsUnsupportedInputsAndDeduplicatesOtherSources() {
        var simulatedSource = new IngestionSource() {
            @Override public String sourceId() { return "simulation"; }
            @Override public boolean simulation() { return true; }
            @Override public void ingest(SourceContext context, java.util.function.Consumer<Payload> output) {}
        };
        assertFalse(sink.supports(simulatedSource, benchmark(ownedId, "single", "1000").body()));
        assertFalse(sink.supports(SOURCE,
                new Payload.Price(Long.toString(ownedId), null, BigDecimal.TEN, "SGD")));

        var invalidReference = new Payload(
                TechApiBenchmarkSource.ID, "invalid-reference", Instant.now(),
                new Payload.Benchmark("external-id", "single", BigDecimal.TEN, "points"));
        assertThrows(IllegalStateException.class,
                () -> sink.accept("run-invalid", invalidReference));

        var thirdParty = new Payload(
                "manual-benchmark", "manual-1", Instant.parse("2026-10-19T00:00:00Z"),
                new Payload.Benchmark(Long.toString(ownedId), "manual", BigDecimal.TEN,
                        "points", true, null));
        assertEquals(IngestionSink.Result.ACCEPTED, sink.accept("run-1", thirdParty));
        assertEquals(IngestionSink.Result.DUPLICATE, sink.accept("run-2", thirdParty));
    }

    private long product(String model) {
        long productId = db.queryForObject("""
                INSERT INTO products(brand,model_name,category,status)
                VALUES ('TechApiSinkTest',?,'SMARTPHONE','VERIFIED') RETURNING id
                """, Long.class, model);
        db.update("INSERT INTO phone(product_id,chipset) VALUES (?,'Snapdragon 8 Elite')", productId);
        return productId;
    }

    private Payload benchmark(long productId, String name, String score) {
        return benchmark(productId, name, score, Instant.parse("2026-10-19T00:00:00Z"));
    }

    private Payload benchmark(long productId, String name, String score, Instant observedAt) {
        return new Payload(TechApiBenchmarkSource.ID, productId + ":" + name, observedAt,
                new Payload.Benchmark(Long.toString(productId), name, new BigDecimal(score),
                        "points", true, PROVENANCE));
    }
}
