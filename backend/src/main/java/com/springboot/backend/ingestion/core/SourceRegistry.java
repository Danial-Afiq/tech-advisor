package com.springboot.backend.ingestion.core;

import com.springboot.backend.ingestion.config.IngestionSettings;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class SourceRegistry {
    private final Map<String, IngestionSource> sources = new TreeMap<>();
    private final IngestionSettings settings;
    public SourceRegistry(List<IngestionSource> adapters, IngestionSettings settings) {
        this.settings = settings;
        for (var source : adapters) {
            if (!source.sourceId().matches("[a-z0-9-]{1,80}")
                    || sources.putIfAbsent(source.sourceId(), source) != null
                    || source.cooldown().isNegative())
                throw new IllegalArgumentException("Invalid or duplicate source ID/policy");
        }
        for (String id : settings.enabledSources())
            if (!sources.containsKey(id)) throw new IllegalArgumentException("Unknown enabled source: " + id);
    }
    public List<String> select(List<String> requested) {
        List<String> ids = requested == null || requested.isEmpty() ? settings.enabledSources() : requested;
        if (ids.isEmpty() || ids.stream().anyMatch(id -> !settings.enabledSources().contains(id)))
            throw new IllegalArgumentException("Select at least one enabled source");
        // Ordered by each source's own declared priority() - not alphabetically. sourceId is only
        // a tiebreaker for determinism between two sources at the same priority.
        return ids.stream().distinct()
                .sorted(Comparator.comparingInt((String id) -> sources.get(id).priority()).thenComparing(Comparator.naturalOrder()))
                .toList();
    }
    public IngestionSource get(String id) { return Objects.requireNonNull(sources.get(id)); }
    public Collection<IngestionSource> all() { return sources.values(); }
    public boolean enabled(String id) { return settings.enabledSources().contains(id); }
}
