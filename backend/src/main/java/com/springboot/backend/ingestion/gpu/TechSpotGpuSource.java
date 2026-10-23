package com.springboot.backend.ingestion.gpu;

import com.springboot.backend.ingestion.core.IngestionSource;
import com.springboot.backend.ingestion.core.Payload;
import com.springboot.backend.ingestion.core.SourceContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ticket 1.3 — desktop GPU specs from TechSpot's GPU database (https://www.techspot.com/specs/gpu/).
 *
 * <p>The catalogue is four list pages of about 160 cards, far more than one run can read inside
 * the shared 10-request / 60 s budget at five seconds per page. Each run reads one list page and
 * then as many of that page's not-yet-tried detail pages as the budget allows, keeping one
 * request spare for a retry. The cursor in ingestion_cursors records the page and the cards
 * already tried on it; it moves on only once every card on the page has been tried, so a card
 * the validator drops cannot pin the sweep. After the last page the cursor wraps to page 1 and
 * marks the sweep complete, which starts the weekly wait before the next refresh sweep.
 *
 * <p>Only URLs {@link TechSpotGpuParser#parseList} accepts are ever requested, so nothing
 * outside /specs/gpu/ (compare pages, price pages) is fetched. Cards whose listed name the
 * validator would exclude (laptop, workstation) are marked tried without a request.
 *
 * <p>The batch is emitted before the run's deadline: the orchestrator rejects output after it,
 * so detail fetches stop once {@link #FETCH_WINDOW} has passed. A failed detail fetch stops the
 * run after the cards read so far are emitted and the cursor saved, then the failure is rethrown
 * so the orchestrator applies its usual cooldown or retry-later.
 */
@Component
public class TechSpotGpuSource implements IngestionSource {
    static final String ID = "techspot-gpu-specs";
    static final String SOURCE = "techspot";
    static final Duration HOST_INTERVAL = Duration.ofSeconds(5);
    /** Latest start for a detail fetch: 5 s pacing plus the 20 s HTTP timeout still ends inside 60 s. */
    static final Duration FETCH_WINDOW = Duration.ofSeconds(35);
    static final Duration SWEEP_INTERVAL = Duration.ofDays(7);
    private static final String LIST_PATH = "/specs/gpu/";
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

    /** The HTTP read, separate so tests can serve fixtures; production uses {@link SourceContext#get}. */
    @FunctionalInterface
    interface Fetch { byte[] get(URI url) throws Exception; }

    /** The persisted {@code ingestion_cursors.state} shape; {@code done} holds TechSpot card ids. */
    record Cursor(int page, List<String> done, boolean swept) {
        static final Cursor START = new Cursor(1, List.of(), false);
        Cursor { done = done == null ? List.of() : List.copyOf(done); }
    }

    private final JdbcTemplate db;
    private final Pattern excluded;

    public TechSpotGpuSource(JdbcTemplate db, GpuValidationSettings settings) {
        this.db = db;
        this.excluded = Pattern.compile(settings.excludedNamePattern());
    }

    @Override public String sourceId() { return ID; }
    /** After the seed (10) so a scraped card refreshes a seeded one, before the review sources. */
    @Override public int priority() { return 20; }
    @Override public Optional<Duration> scheduleInterval() { return Optional.of(SWEEP_INTERVAL); }
    /** Due again at every shared wake-up until a full sweep of the list pages has finished. */
    @Override public boolean scheduleCycleComplete() { return loadCursor().swept(); }

    @Override
    public void ingest(SourceContext context, Consumer<Payload> output) throws Exception {
        context.minimumInterval(TechSpotGpuParser.HOST, HOST_INTERVAL);
        run(context, context::get, output);
    }

    void run(SourceContext context, Fetch fetch, Consumer<Payload> output) throws Exception {
        Instant start = context.now();
        int startingRequests = context.remainingRequests();
        Cursor cursor = loadCursor();
        URI listUrl = listUrl(cursor.page());
        var list = TechSpotGpuParser.parseList(text(fetch.get(listUrl)), listUrl);
        if (list.listings().isEmpty()) { saveCursor(Cursor.START); return; }

        Set<String> done = new LinkedHashSet<>(cursor.done());
        // One request for the list page and one kept spare, also if the list page needed a retry.
        int detailBudget = Math.min(startingRequests - 2, context.remainingRequests() - 1);
        int fetched = 0;
        List<Payload.GpuSpec> specs = new ArrayList<>();
        Exception failure = null;
        for (var listing : list.listings()) {
            String id = id(listing.url());
            if (done.contains(id)) continue;
            if (excluded.matcher(listing.name()).find()) { done.add(id); continue; }
            if (fetched >= detailBudget || !Duration.between(start, context.now()).minus(FETCH_WINDOW).isNegative()) break;
            fetched++;
            try { TechSpotGpuParser.parseDetail(text(fetch.get(listing.url())), listing.url()).ifPresent(specs::add); }
            catch (SourceContext.HttpFailure gone) { if (gone.status != 404 && gone.status != 410) { failure = gone; break; } }
            catch (Exception e) { failure = e; break; }
            done.add(id);
        }
        if (!specs.isEmpty())
            output.accept(new Payload(ID, "page-" + cursor.page(), context.now(),
                    new Payload.GpuCatalogBatch(SOURCE, specs)));
        saveCursor(next(cursor.page(), list, done));
        if (failure != null) throw failure;
    }

    /** Stay on a page until every card on it has been tried; wrap after the last page. */
    static Cursor next(int page, TechSpotGpuParser.ListPage list, Set<String> done) {
        boolean pageDone = list.listings().stream().allMatch(listing -> done.contains(id(listing.url())));
        if (!pageDone) return new Cursor(page, List.copyOf(done), false);
        return page >= list.lastPage() ? new Cursor(1, List.of(), true) : new Cursor(page + 1, List.of(), false);
    }

    static URI listUrl(int page) {
        return URI.create("https://" + TechSpotGpuParser.HOST + LIST_PATH + (page <= 1 ? "" : page + "/"));
    }

    /** The numeric card id that starts every detail path, e.g. 254404 in /specs/gpu/254404-nvidia-geforce-rtx-4070.html. */
    static String id(URI detail) {
        String slug = detail.getPath().substring(LIST_PATH.length());
        return slug.substring(0, slug.indexOf('-'));
    }

    private static String text(byte[] body) {
        return new String(body, StandardCharsets.UTF_8);
    }

    // Package-private so a real-Postgres test exercises the SQL, as MobileApiSmartphoneSource does.
    Cursor loadCursor() {
        List<String> rows = db.queryForList(
                "SELECT state::text FROM ingestion_cursors WHERE source_id = ?", String.class, ID);
        if (rows.isEmpty()) return Cursor.START;
        try { return JSON.readValue(rows.getFirst(), Cursor.class); }
        catch (RuntimeException unreadable) { return Cursor.START; }
    }

    void saveCursor(Cursor cursor) {
        db.update("""
                INSERT INTO ingestion_cursors (source_id, state, updated_at)
                VALUES (?, ?::jsonb, CURRENT_TIMESTAMP)
                ON CONFLICT (source_id) DO UPDATE SET
                    state = EXCLUDED.state, updated_at = EXCLUDED.updated_at
                """, ID, JSON.writeValueAsString(cursor));
    }
}
