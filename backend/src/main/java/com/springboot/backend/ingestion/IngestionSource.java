package com.springboot.backend.ingestion;

import java.time.Duration;
import java.util.function.Consumer;

/** Implement fetching and translation only; runner owns admission, timing and logs. */
public interface IngestionSource {
    String sourceId();
    default boolean simulation() { return false; }
    default Duration cooldown() { return Duration.ofMinutes(15); }
    void ingest(SourceContext context, Consumer<Payload> output) throws Exception;
}
