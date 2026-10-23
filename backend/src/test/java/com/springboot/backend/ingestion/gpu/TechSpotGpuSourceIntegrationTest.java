package com.springboot.backend.ingestion.gpu;

import static org.junit.jupiter.api.Assertions.*;

import com.springboot.backend.ingestion.core.Payload;
import com.springboot.backend.ingestion.core.SourceContext;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real Postgres for the cursor SQL; TechSpot itself is replaced by the committed fixtures. Every
 * list page is served from list-page-1 (its pagination says there are 4 pages) and every detail
 * page is the RTX 4070 page renamed to the listed card, so no request leaves the test.
 */
@SpringBootTest(properties = "ingestion.reconciliation-enabled=false")
class TechSpotGpuSourceIntegrationTest {
    @Autowired TechSpotGpuSource source;
    @Autowired JdbcTemplate db;

    private final List<URI> requested = new ArrayList<>();
    private String listPage;
    private String detailPage;
    private Map<URI, String> names;

    @BeforeEach
    void reset() throws Exception {
        assertTrue(db.queryForObject("SELECT current_database()", String.class).endsWith("_test"),
                "Integration tests require a dedicated database whose name ends in _test");
        clearCursor();
        listPage = fixture("list-page-1.html");
        detailPage = fixture("rtx-4070.html");
        names = TechSpotGpuParser.parseList(listPage, TechSpotGpuSource.listUrl(1)).listings().stream()
                .collect(Collectors.toMap(TechSpotGpuParser.Listing::url, TechSpotGpuParser.Listing::name));
    }

    @AfterEach
    void clearCursor() {
        db.update("DELETE FROM ingestion_cursors WHERE source_id = ?", TechSpotGpuSource.ID);
    }

    @Test
    void aRunReadsOneListPageThenEightDetailsAndNeverLeavesSpecsGpu() throws Exception {
        List<Payload> out = run(Clock.systemUTC());

        assertEquals(9, requested.size(), "1 list page + 8 details leaves one of 10 requests spare");
        assertEquals(TechSpotGpuSource.listUrl(1), requested.getFirst());
        assertTrue(requested.stream().allMatch(url -> url.getHost().equals("www.techspot.com")
                && url.getPath().startsWith("/specs/gpu/")), requested.toString());
        assertEquals(1, out.size());
        var batch = (Payload.GpuCatalogBatch) out.getFirst().body();
        assertEquals("techspot", batch.source());
        assertEquals(8, batch.gpus().size());
        var cursor = source.loadCursor();
        assertEquals(1, cursor.page());
        assertEquals(8, cursor.done().size());
        assertFalse(cursor.swept());
        assertFalse(source.scheduleCycleComplete());
    }

    @Test
    void theNextRunSkipsCardsAlreadyTriedOnThePage() throws Exception {
        run(Clock.systemUTC());
        List<URI> first = List.copyOf(requested.subList(1, requested.size()));
        requested.clear();
        run(Clock.systemUTC());

        assertEquals(9, requested.size());
        assertTrue(requested.subList(1, requested.size()).stream().noneMatch(first::contains));
        assertEquals(16, source.loadCursor().done().size());
    }

    @Test
    void theCursorMovesOnOnlyOnceEveryCardOnThePageHasBeenTried() throws Exception {
        var ids = names.keySet().stream().map(TechSpotGpuSource::id).toList();
        source.saveCursor(new TechSpotGpuSource.Cursor(2, ids.subList(1, ids.size()), false));

        run(Clock.systemUTC());

        assertEquals(TechSpotGpuSource.listUrl(2), requested.getFirst());
        assertEquals(2, requested.size());
        assertEquals(new TechSpotGpuSource.Cursor(3, List.of(), false), source.loadCursor());
    }

    @Test
    void finishingTheLastPageWrapsToPageOneAndCompletesTheSweep() throws Exception {
        var ids = names.keySet().stream().map(TechSpotGpuSource::id).toList();
        source.saveCursor(new TechSpotGpuSource.Cursor(4, ids.subList(1, ids.size()), false));

        run(Clock.systemUTC());

        assertEquals(new TechSpotGpuSource.Cursor(1, List.of(), true), source.loadCursor());
        assertTrue(source.scheduleCycleComplete());
    }

    @Test
    void detailFetchesStopOnceTheFetchWindowHasPassed() throws Exception {
        // Each reading of the clock moves 10 s on: details start at +10, +20 and +30 s only.
        List<Payload> out = run(new SteppingClock(Duration.ofSeconds(10)));

        assertEquals(4, requested.size());
        assertEquals(3, ((Payload.GpuCatalogBatch) out.getFirst().body()).gpus().size());
    }

    @Test
    void excludedCardNamesAreMarkedTriedWithoutARequest() throws Exception {
        listPage = """
                <div class="ProdListInfo"><h4><a href="/specs/gpu/1-nvidia-quadro-rtx-4000.html">Nvidia Quadro RTX 4000</a></h4></div>
                <div class="ProdListInfo"><h4><a href="/specs/gpu/2-nvidia-geforce-rtx-4070.html">Nvidia GeForce RTX 4070</a></h4></div>
                """;
        names = Map.of(URI.create("https://www.techspot.com/specs/gpu/2-nvidia-geforce-rtx-4070.html"), "Nvidia GeForce RTX 4070");

        run(Clock.systemUTC());

        assertEquals(List.of(TechSpotGpuSource.listUrl(1),
                URI.create("https://www.techspot.com/specs/gpu/2-nvidia-geforce-rtx-4070.html")), requested);
        assertTrue(source.loadCursor().swept(), "single-page list with both cards tried completes the sweep");
    }

    @Test
    void aFailedDetailStillEmitsWhatWasReadSavesTheCursorAndRethrows() throws Exception {
        var failing = new ArrayList<URI>();
        var out = new ArrayList<Payload>();
        try (var context = new SourceContext(Clock.systemUTC(), () -> {})) {
            var boom = assertThrows(IllegalStateException.class, () -> source.run(context, url -> {
                requested.add(url);
                if (requested.size() == 4) { failing.add(url); throw new IllegalStateException("boom"); }
                return serve(url);
            }, out::add));
            assertEquals("boom", boom.getMessage());
        }

        assertEquals(2, ((Payload.GpuCatalogBatch) out.getFirst().body()).gpus().size());
        var cursor = source.loadCursor();
        assertEquals(2, cursor.done().size());
        assertFalse(cursor.done().contains(TechSpotGpuSource.id(failing.getFirst())), "the failed card is retried next run");
    }

    private List<Payload> run(Clock clock) throws Exception {
        var out = new ArrayList<Payload>();
        try (var context = new SourceContext(clock, () -> {})) {
            source.run(context, url -> { requested.add(url); return serve(url); }, out::add);
        }
        return out;
    }

    private byte[] serve(URI url) {
        String body = url.getPath().endsWith(".html")
                ? detailPage.replace("Nvidia GeForce RTX 4070</h1>", names.get(url) + "</h1>")
                : listPage;
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static String fixture(String name) throws Exception {
        try (InputStream in = TechSpotGpuSourceIntegrationTest.class.getResourceAsStream("/techspot/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** A clock that moves a fixed step forward every time it is read. */
    private static final class SteppingClock extends Clock {
        private final Duration step;
        private Instant now = Instant.parse("2026-10-23T00:00:00Z");
        SteppingClock(Duration step) { this.step = step; }
        @Override public synchronized Instant instant() { Instant current = now; now = now.plus(step); return current; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }
}
