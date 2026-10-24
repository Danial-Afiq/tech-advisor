package com.springboot.backend.ingestion.gpu;

import static org.junit.jupiter.api.Assertions.*;

import com.springboot.backend.ingestion.core.IngestionSink;
import com.springboot.backend.ingestion.core.Payload;
import com.springboot.backend.ingestion.core.SourceContext;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
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
    void seedHoldsTheTwelveConfirmedCardsAndEveryOnePassesValidation() throws Exception {
        assertEquals("seed:gpus.v1", GpuSeedSource.load().source());
        assertEquals(12, seed.size());
        for (var card : seed)
            assertEquals(java.util.Optional.empty(), validator.violation(card), card.modelName());
    }

    @Test
    void everySeedCardLandsInProductsAndGpuAsCategoryGpu() throws Exception {
        List<Payload> payloads = run();
        assertEquals(1, payloads.size());
        payloads.getFirst().validate(GpuSeedSource.ID);
        assertEquals(IngestionSink.Result.ACCEPTED, sink.accept("seed-run", payloads.getFirst()));

        for (var card : seed) {
            var row = db.queryForMap("""
                    SELECT p.category, g.vram, g.base_clock_mhz, g.source,
                           (SELECT count(*) FROM price_history ph
                            WHERE ph.product_id = p.id AND ph.source = 'launch_msrp:seed:gpus.v1') AS msrp_rows
                    FROM products p JOIN gpu g ON g.product_id = p.id
                    WHERE p.brand = ? AND p.model_name = ?
                    """, card.brand(), card.modelName());
            assertEquals("GPU", row.get("category"), card.modelName());
            assertEquals(0, card.vramGb().compareTo((java.math.BigDecimal) row.get("vram")), card.modelName());
            assertEquals(card.baseClockMhz(), row.get("base_clock_mhz"), card.modelName());
            assertEquals("seed:gpus.v1", row.get("source"), card.modelName());
            assertEquals(1L, row.get("msrp_rows"), card.modelName());
        }
    }

    @Test
    void cardsAlreadyInTheCatalogueAreLeftOutSoARerunEmitsNothing() throws Exception {
        sink.accept("seed-run-1", run().getFirst());
        assertEquals(List.of(), run());

        var rtx4070 = seed.stream().filter(card -> card.modelName().equals("GeForce RTX 4070")).findFirst().orElseThrow();
        deleteRows(rtx4070);
        List<Payload> rerun = run();
        assertEquals(1, rerun.size());
        assertEquals(List.of(rtx4070), ((Payload.GpuCatalogBatch) rerun.getFirst().body()).gpus());
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
