package com.springboot.backend.ingestion;

import java.time.*;
import java.util.concurrent.ScheduledFuture;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.slf4j.LoggerFactory;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="ingestion.reconciliation-enabled", havingValue="true", matchIfMissing=true)
public class IngestionSchedule {
    private final IngestionSettings settings;
    private final RunStore store;
    private final IngestionOrchestrator runner;
    private final ThreadPoolTaskScheduler scheduler;
    private final Clock clock;
    private ScheduledFuture<?> armed;
    public IngestionSchedule(IngestionSettings settings, RunStore store, IngestionOrchestrator runner,
                             ThreadPoolTaskScheduler scheduler, Clock clock) {
        this.settings = settings; this.store = store; this.runner = runner; this.scheduler = scheduler; this.clock = clock;
    }
    // Recovery/dispatch sweep does not scrape each time: durable nextDue gates admission.
    @Scheduled(initialDelay = 1000, fixedDelay = 10000)
    public synchronized void reconcile() {
        try {
            runner.kick();
            if (!settings.schedulingEnabled()) return;
            var state = store.state();
            if (armed != null) armed.cancel(false);
            Instant due = state.nextDue.isBefore(clock.instant()) ? clock.instant() : state.nextDue;
            armed = scheduler.schedule(() -> {
                try { runner.scheduled(); }
                catch (Exception e) { LoggerFactory.getLogger(getClass()).error("Scheduled ingestion failed: {}", e.getClass().getSimpleName()); }
            }, due);
        } catch (Exception e) { LoggerFactory.getLogger(getClass()).error("Ingestion reconciliation failed: {}", e.getClass().getSimpleName()); }
    }
}
