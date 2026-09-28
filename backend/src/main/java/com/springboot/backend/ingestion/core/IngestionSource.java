package com.springboot.backend.ingestion.core;

import java.time.Duration;
import java.util.function.Consumer;

/** Implement fetching and translation only; runner owns admission, timing and logs. */
public interface IngestionSource {
    String sourceId();
    default boolean simulation() { return false; }
    default Duration cooldown() { return Duration.ofMinutes(15); }
    /**
     * Execution order within one run when multiple sources are selected -
     * lower runs first. Deliberately explicit rather than left to fall out
     * of alphabetical sourceId sorting, which is what SourceRegistry used to
     * do: correct only by coincidence (mobileapi-smartphone happened to sort
     * before searchapi-google-product-reviews), and silently wrong the
     * moment a source with an earlier-sorting id existed. Default is a
     * middle value so most sources need no opinion; a source that must run
     * before another (catalogue creation before anything that reads the
     * catalogue) overrides this.
     */
    default int priority() { return 100; }
    void ingest(SourceContext context, Consumer<Payload> output) throws Exception;
}
