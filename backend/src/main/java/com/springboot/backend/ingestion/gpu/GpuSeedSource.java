package com.springboot.backend.ingestion.gpu;

import com.springboot.backend.ingestion.core.IngestionSource;
import com.springboot.backend.ingestion.core.Payload;
import com.springboot.backend.ingestion.core.SourceContext;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ticket 1.3 — prefills the starter GPU catalogue from {@code classpath:seed/gpus.v1.json}.
 * No network: the hand-entered cards go through {@link GpuCatalogSink} like scraped ones, so
 * they get the same validation and upsert.
 *
 * <p>The seed is a prefill, not a source of truth. A card already in the catalogue is left
 * out of the batch, so re-running never overwrites values TechSpot has since refreshed, and
 * a run where every card exists emits nothing. Opt-in through
 * {@code INGESTION_ENABLED_SOURCES=gpu-seed}; trigger once via the admin runs endpoint.
 */
@Component
public class GpuSeedSource implements IngestionSource {
    static final String ID = "gpu-seed";
    static final String SEED = "seed/gpus.v1.json";

    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

    private final JdbcTemplate db;

    public GpuSeedSource(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public String sourceId() {
        return ID;
    }

    /** Catalogue creation runs before sources that read the catalogue, as MobileAPI does. */
    @Override
    public int priority() {
        return 10;
    }

    @Override
    public void ingest(SourceContext context, Consumer<Payload> output) throws Exception {
        context.check();
        Payload.GpuCatalogBatch seed = load();
        var existing = new HashSet<>(db.queryForList(
                "SELECT lower(brand) || '|' || lower(model_name) FROM products WHERE category = 'GPU'",
                String.class));
        List<Payload.GpuSpec> missing = seed.gpus().stream().filter(card -> !exists(existing, card)).toList();
        if (missing.isEmpty()) return;
        output.accept(new Payload(ID, seed.source(), context.now(),
                new Payload.GpuCatalogBatch(seed.source(), missing)));
    }

    static Payload.GpuCatalogBatch load() throws Exception {
        try (InputStream in = GpuSeedSource.class.getClassLoader().getResourceAsStream(SEED)) {
            if (in == null) throw new IllegalStateException("Missing classpath resource " + SEED);
            return JSON.readValue(in, Payload.GpuCatalogBatch.class);
        }
    }

    /** A card the sink would drop anyway (unknown brand, no name) stays in, so the drop is logged. */
    private static boolean exists(Set<String> existing, Payload.GpuSpec card) {
        String brand = GpuNames.brand(card.brand());
        String model = GpuNames.model(brand, card.modelName());
        return brand != null && model != null && existing.contains(GpuNames.key(brand, model));
    }
}
