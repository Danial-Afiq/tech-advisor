package com.springboot.backend.ingestion.core;

import com.springboot.backend.ingestion.config.IngestionSettings;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceRegistryTests {
    private static IngestionSource source(String id, int priority) {
        return new IngestionSource() {
            public String sourceId() { return id; }
            public int priority() { return priority; }
            public void ingest(SourceContext c, Consumer<Payload> o) {}
        };
    }

    @Test void selectOrdersByPriorityNotAlphabetically() {
        // Deliberately misleading ids: alphabetically "aaa-second" would sort first, but its
        // priority says it should run second. Proves ordering is priority-driven, not a side
        // effect of sourceId sorting (which is exactly the bug this replaces).
        var registry = new SourceRegistry(
                List.of(source("aaa-second", 200), source("zzz-first", 10)),
                new IngestionSettings(false, null, List.of("aaa-second", "zzz-first")));
        assertEquals(List.of("zzz-first", "aaa-second"), registry.select(null));
    }

    @Test void selectTiebreaksByIdWhenPrioritiesAreEqual() {
        var registry = new SourceRegistry(
                List.of(source("b-source", 100), source("a-source", 100)),
                new IngestionSettings(false, null, List.of("a-source", "b-source")));
        assertEquals(List.of("a-source", "b-source"), registry.select(null));
    }
}
