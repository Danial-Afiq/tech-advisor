package com.springboot.backend.ingestion;

import java.time.Instant;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ingestion")
public record IngestionSettings(boolean schedulingEnabled, Instant anchor, List<String> enabledSources) {
    public IngestionSettings {
        enabledSources = enabledSources == null ? List.of() : List.copyOf(enabledSources);
        if (schedulingEnabled && anchor == null)
            throw new IllegalArgumentException("INGESTION_ANCHOR is required when scheduling is enabled");
    }
}
