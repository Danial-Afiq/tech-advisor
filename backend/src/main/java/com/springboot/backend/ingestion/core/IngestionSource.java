package com.springboot.backend.ingestion.core;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;

/** Implement fetching and translation only; runner owns admission, timing and logs. */
public interface IngestionSource {
    int DEFAULT_REQUEST_LIMIT = 10;
    Duration DEFAULT_EXECUTION_BUDGET = Duration.ofSeconds(60);

    String sourceId();
    default boolean simulation() { return false; }
    default Duration cooldown() { return Duration.ofMinutes(15); }
    /** Maximum HTTP attempts available through SourceContext for one source run. */
    default int requestLimit() { return DEFAULT_REQUEST_LIMIT; }
    /** Wall-clock budget for one source run. */
    default Duration executionBudget() { return DEFAULT_EXECUTION_BUDGET; }
    /** Empty means this source follows the shared ingestion cadence. */
    default Optional<Duration> scheduleInterval() { return Optional.empty(); }
    /** False keeps an incomplete bounded batch due at the next shared scheduler wake-up. */
    default boolean scheduleCycleComplete() { return true; }
    /** Optional durable source progress exposed to the admin UI. */
    default Optional<Progress> progress() { return Optional.empty(); }
    record Progress(
            String phase,
            long completedUnits,
            long totalUnits,
            long observationsIngested,
            long productsCovered) {}
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
