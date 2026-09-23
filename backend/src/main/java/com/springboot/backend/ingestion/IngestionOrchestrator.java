package com.springboot.backend.ingestion;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Service
public class IngestionOrchestrator {
    private static final Logger LOG = LoggerFactory.getLogger(IngestionOrchestrator.class);
    private final RunStore store;
    private final SourceRegistry registry;
    private final List<IngestionSink> sinks;
    private final Clock clock;
    private final ThreadPoolTaskScheduler scheduler;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("ingestion-worker").daemon().factory());
    // No queue: a source ignoring interruption cannot spawn unlimited replacement threads.
    private final ExecutorService sourceWorker = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), Thread.ofPlatform().name("ingestion-source").daemon().factory());
    private final AtomicBoolean busy = new AtomicBoolean();

    public IngestionOrchestrator(RunStore store, SourceRegistry registry, List<IngestionSink> sinks,
                                 Clock clock, ThreadPoolTaskScheduler scheduler) {
        this.store = store; this.registry = registry; this.sinks = sinks; this.clock = clock; this.scheduler = scheduler;
    }
    public RunLog manual(List<String> sources, String actor, String key, String reason) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,128}") || reason != null && reason.length() > 500)
            throw new IllegalArgumentException("Provide an 8-128 character idempotency key and a reason of at most 500 characters");
        var ids = registry.select(sources);
        var run = store.admit(ids, actor, key, reason, false, ids.stream().allMatch(id -> registry.get(id).simulation()));
        kick(); return run;
    }
    public void scheduled() {
        var ids = registry.select(null);
        store.admit(ids, "scheduler", null, null, true, ids.stream().allMatch(id -> registry.get(id).simulation()));
        kick();
    }
    public void kick() {
        if (!busy.compareAndSet(false, true)) return;
        try {
            worker.submit(() -> {
                try {
                    String owner = UUID.randomUUID().toString();
                    var run = store.claim(owner);
                    if (run != null) execute(run, owner);
                } catch (Exception e) { LOG.error("Ingestion worker stopped: {}", e.getClass().getSimpleName()); }
                finally { busy.set(false); }
            });
        } catch (RejectedExecutionException e) { busy.set(false); /* Durable accepted work is recovered by the sweep. */ }
    }
    private void execute(RunLog run, String owner) {
        var ownershipLost = new AtomicBoolean();
        Runnable check = () -> {
            if (ownershipLost.get()) throw new IllegalStateException("Ingestion ownership lost");
            store.heartbeat(run.runId, owner);
        };
        var heartbeat = scheduler.scheduleAtFixedRate(() -> {
            try { check.run(); } catch (Exception e) { ownershipLost.set(true); }
        }, Duration.ofSeconds(10));
        try {
            for (String id : run.sourceIds) {
                check.run();
                var adapter = registry.get(id);
                var result = new RunLog.SourceResult(); result.sourceId = id; result.startedAt = clock.instant();
                run.sources.add(result);
                if (!store.startSource(run.runId, owner, adapter)) {
                    run.skippedSourceCount++;
                    result.status = "SKIPPED_COOLDOWN"; result.finishedAt = clock.instant();
                    store.progress(run, owner); continue;
                }
                store.progress(run, owner);
                try (var context = new SourceContext(clock, check)) {
                    Future<?> future = sourceWorker.submit(() -> {
                        try {
                            var seen = new HashSet<String>();
                            adapter.ingest(context, payload -> {
                                synchronized (run) {
                                    context.check();
                                    if (result.processedPayloadCount + result.duplicatePayloadCount + result.rejectedPayloadCount >= 1000)
                                        throw new IllegalStateException("Source payload limit exceeded");
                                    try { if (payload == null) throw new IllegalArgumentException(); payload.validate(id); }
                                    catch (IllegalArgumentException invalid) {
                                        result.rejectedPayloadCount++; run.rejectedPayloadCount++;
                                        result.errorCount++; run.errorCount++; store.progress(run, owner); return;
                                    }
                                    String identity = payload.body().getClass().getSimpleName() + ":" + payload.externalId();
                                    if (!seen.add(identity)) { duplicate(run, result); }
                                    else {
                                        var matching = sinks.stream().filter(sink -> sink.supports(adapter, payload.body())).toList();
                                        if (matching.size() != 1) throw new IllegalStateException("Exactly one typed sink must accept this source payload");
                                        if (matching.getFirst().accept(run.runId, payload, context) == IngestionSink.Result.DUPLICATE) duplicate(run, result);
                                        else { result.processedPayloadCount++; run.processedPayloadCount++; }
                                    }
                                    store.progress(run, owner);
                                }
                            });
                        } catch (Exception e) { throw new CompletionException(e); }
                    });
                    try { future.get(60, TimeUnit.SECONDS); }
                    finally { context.close(); future.cancel(true); }
                    result.status = result.errorCount == 0 ? "SUCCESS" : "PARTIAL_FAILURE";
                } catch (Exception e) {
                    synchronized (run) {
                        Throwable error = e;
                        while ((error instanceof ExecutionException || error instanceof CompletionException) && error.getCause() != null) error = error.getCause();
                        if (error instanceof SourceContext.RetryLater retry)
                            store.deferSource(run.runId, owner, id, retry.until);
                        result.status = "FAILED"; result.errorCount++; result.errorStackCount++;
                        run.errorCount++; run.errorStackCount++;
                        result.errors.add(error.getClass().getSimpleName());
                        if (error instanceof IngestionFailure failure) result.errors.add(failure.code().name());
                        Arrays.stream(error.getStackTrace()).filter(frame -> frame.getClassName().startsWith("com.springboot.backend.ingestion"))
                                .limit(5).map(StackTraceElement::toString).forEach(result.errors::add);
                    }
                }
                synchronized (run) {
                    result.finishedAt = clock.instant(); store.progress(run, owner);
                }
            }
            run.status = run.skippedSourceCount == run.sourceIds.size() ? "SKIPPED"
                    : run.errorCount == 0 ? "SUCCESS" : run.processedPayloadCount > 0 ? "PARTIAL_FAILURE" : "FAILED";
            store.finish(run, owner);
        } finally { heartbeat.cancel(false); }
    }
    private static void duplicate(RunLog run, RunLog.SourceResult result) { result.duplicatePayloadCount++; run.duplicatePayloadCount++; }
    @PreDestroy public void close() { worker.shutdownNow(); sourceWorker.shutdownNow(); }
}
