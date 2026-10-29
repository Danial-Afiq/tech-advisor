package com.springboot.backend.ingestion.gpu;

import static org.junit.jupiter.api.Assertions.*;

import com.springboot.backend.ingestion.core.IngestionSink;
import com.springboot.backend.ingestion.core.Payload;
import com.springboot.backend.ingestion.core.SourceContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The seed holds real card names, so the test deletes exactly those rows before and after and
 * never touches anything else in the shared _test database.
 */
@SpringBootTest(properties = "ingestion.reconciliation-enabled=false")
class GpuSeedSourceIntegrationTest {
    /** The gpus.v1 cards and their TechSpot launch prices, which v2 must keep matching. */
    private static final Map<String, Integer> V1_LAUNCH_MSRP = Map.ofEntries(
            Map.entry("GeForce GTX 1060 6GB", 299), Map.entry("GeForce GTX 1660 Super", 229),
            Map.entry("GeForce RTX 2060 6GB", 349), Map.entry("GeForce RTX 3060 12GB", 329),
            Map.entry("GeForce RTX 3070", 499), Map.entry("GeForce RTX 4060", 299),
            Map.entry("GeForce RTX 4070", 599), Map.entry("GeForce RTX 5070", 549),
            Map.entry("Radeon RX 580 8GB", 229), Map.entry("Radeon RX 6600", 329),
            Map.entry("Radeon RX 7800 XT", 499), Map.entry("Arc A770 16GB", 349));

    @Autowired GpuSeedSource source;
    @Autowired GpuCatalogSink sink;
    @Autowired GpuSpecValidator validator;
    @Autowired JdbcTemplate db;

    private List<Payload.GpuSpec> seed;

    @BeforeEach
    void reset() throws Exception {
        assertTrue(db.queryForObject("SELECT current_database()", String.class).endsWith("_test"),
                "Integration tests require a dedicated database whose name ends in _test");
        seed = GpuSeedSource.load().gpus();
        deleteSeedRows();
    }

    @AfterEach
    void cleanUp() {
        deleteSeedRows();
    }

    @Test
    void everySeedCardPassesValidationUnderAUniqueName() throws Exception {
        assertEquals("seed:gpus.v2", GpuSeedSource.load().source());
        assertTrue(seed.size() > GpuSeedSource.BATCH_SIZE, "the seed is expected to span several batches");
        var keys = new HashSet<String>();
        for (var card : seed) {
            assertEquals(java.util.Optional.empty(), validator.violation(card), card.modelName());
            assertTrue(keys.add(GpuNames.key(card.brand(), card.modelName())), "duplicate " + card.modelName());
        }
    }

    @Test
    void theV1CardsKeepTheirNamesAndLaunchPrices() {
        V1_LAUNCH_MSRP.forEach((name, msrp) -> {
            var card = seed.stream().filter(c -> c.modelName().equals(name)).findFirst()
                    .orElseThrow(() -> new AssertionError("missing v1 card " + name));
            assertEquals(0, card.launchMsrpUsd().compareTo(java.math.BigDecimal.valueOf(msrp)), name);
        });
        assertEquals(V1_LAUNCH_MSRP.size(), seed.stream().filter(c -> c.launchMsrpUsd() != null).count());
    }

    @Test
    void everySeedCardLandsInProductsAndGpuAsCategoryGpu() throws Exception {
        List<Payload> payloads = run();
        assertEquals((seed.size() + GpuSeedSource.BATCH_SIZE - 1) / GpuSeedSource.BATCH_SIZE, payloads.size());
        var ids = new HashSet<String>();
        for (var payload : payloads) {
            payload.validate(GpuSeedSource.ID);
            assertTrue(ids.add(payload.externalId()), "batches must not share an external id");
            assertEquals(IngestionSink.Result.ACCEPTED, sink.accept("seed-run", payload));
        }

        for (var card : seed) {
            var row = db.queryForMap("""
                    SELECT p.category, g.vram, g.base_clock_mhz, g.source,
                           (SELECT count(*) FROM price_history ph
                            WHERE ph.product_id = p.id AND ph.source = 'launch_msrp:seed:gpus.v2') AS msrp_rows
                    FROM products p JOIN gpu g ON g.product_id = p.id
                    WHERE p.brand = ? AND p.model_name = ?
                    """, card.brand(), card.modelName());
            assertEquals("GPU", row.get("category"), card.modelName());
            assertEquals(0, card.vramGb().compareTo((java.math.BigDecimal) row.get("vram")), card.modelName());
            assertEquals(card.baseClockMhz(), row.get("base_clock_mhz"), card.modelName());
            assertEquals("seed:gpus.v2", row.get("source"), card.modelName());
            assertEquals(card.launchMsrpUsd() == null ? 0L : 1L, row.get("msrp_rows"), card.modelName());
        }
    }

    @Test
    void cardsAlreadyInTheCatalogueAreLeftOutSoARerunEmitsNothing() throws Exception {
        for (var payload : run()) sink.accept("seed-run-1", payload);
        assertEquals(List.of(), run());

        var rtx4070 = seed.stream().filter(card -> card.modelName().equals("GeForce RTX 4070")).findFirst().orElseThrow();
        deleteRows(rtx4070);
        List<Payload> rerun = run();
        assertEquals(1, rerun.size());
        assertEquals(List.of(rtx4070), ((Payload.GpuCatalogBatch) rerun.getFirst().body()).gpus());
    }

    @Test
    void aRunPastTheEmitWindowStartsNoFurtherBatches() throws Exception {
        // Each read of the clock is 41 s after the last, so the window has closed by the second batch.
        var ticks = new AtomicLong();
        Clock slow = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.EPOCH.plusSeconds(41 * ticks.getAndIncrement()); }
        };
        var payloads = new ArrayList<Payload>();
        try (var context = new SourceContext(slow, () -> {})) {
            source.ingest(context, payloads::add);
        }
        assertEquals(1, payloads.size());
        assertEquals(GpuSeedSource.BATCH_SIZE, ((Payload.GpuCatalogBatch) payloads.getFirst().body()).gpus().size());
    }

    private List<Payload> run() throws Exception {
        var payloads = new ArrayList<Payload>();
        try (var context = new SourceContext(Clock.systemUTC(), () -> {})) {
            source.ingest(context, payloads::add);
        }
        return payloads;
    }

    private void deleteSeedRows() {
        seed.forEach(this::deleteRows);
    }

    private void deleteRows(Payload.GpuSpec card) {
        String match = "SELECT id FROM products WHERE lower(brand) = lower(?) AND lower(model_name) = lower(?)";
        db.update("DELETE FROM market_events WHERE product_id IN (" + match + ")", card.brand(), card.modelName());
        db.update("DELETE FROM products WHERE id IN (" + match + ")", card.brand(), card.modelName());
    }
}
