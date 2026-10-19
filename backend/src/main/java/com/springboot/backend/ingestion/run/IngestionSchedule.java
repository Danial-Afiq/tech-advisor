package com.springboot.backend.ingestion.run;

import com.springboot.backend.ingestion.config.IngestionSettings;
import com.springboot.backend.ingestion.core.IngestionOrchestrator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="ingestion.reconciliation-enabled", havingValue="true", matchIfMissing=true)
public class IngestionSchedule {
    private static final Logger LOG = LoggerFactory.getLogger(IngestionSchedule.class);
    // Longer than Neon's five-minute idle timeout; only used after errors.
    private static final Duration RETRY_DELAY = Duration.ofMinutes(10);
    private static final Duration CLAIM_GRACE = Duration.ofSeconds(30);

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

    // Recover accepted work on startup, then use one-shot timers instead of querying Neon every 10 seconds.
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void reconcile() {
        try {
            runner.kick();
            armFromState(store.state());
        } catch (Exception e) {
            retry(e);
        }
    }

    private synchronized void wake() {
        try {
            var state = store.state();
            if (state.activeRunId != null) {
                // The lease may have expired after a crash; claim() handles recovery safely.
                runner.kick();
            } else if (settings.schedulingEnabled() && state.nextDue != null
                    && !state.nextDue.isAfter(clock.instant())) {
                runner.scheduled();
            }
            // A scheduled admission moves nextDue; read the persisted value before re-arming.
            armFromState(store.state());
        } catch (Exception e) {
            retry(e);
        }
    }

    private void armFromState(CoordinatorState state) {
        Instant now = clock.instant();
        Instant due = settings.schedulingEnabled() ? state.nextDue : null;
        if (settings.schedulingEnabled() && due == null)
            throw new IllegalStateException("Scheduled ingestion has no nextDue");

        if (state.activeRunId != null) {
            // Do not repeatedly poll while work is running. Check at lease expiry to
            // recover after a crash; a healthy worker continually extends its lease.
            due = state.leaseUntil == null || !state.leaseUntil.isAfter(now)
                    ? now.plus(CLAIM_GRACE) : state.leaseUntil.plusSeconds(1);
        }
        if (due == null) {
            if (armed != null) armed.cancel(false);
            armed = null;
            return;
        }
        arm(due.isBefore(now) ? now : due);
    }

    private void retry(Exception e) {
        LOG.error("Ingestion scheduling/recovery failed: {}", e.getClass().getSimpleName());
        // Keep retrying during outages without continuously waking an idle database.
        try {
            arm(clock.instant().plus(RETRY_DELAY));
        } catch (Exception scheduleError) {
            LOG.error("Could not arm ingestion retry: {}", scheduleError.getClass().getSimpleName());
        }
    }

    private void arm(Instant due) {
        if (armed != null) armed.cancel(false);
        armed = scheduler.schedule(this::wake, due);
    }

    @PreDestroy
    public synchronized void close() {
        if (armed != null) armed.cancel(false);
        armed = null;
    }
}
