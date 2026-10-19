package com.springboot.backend.ingestion.run;

import com.springboot.backend.ingestion.config.IngestionSettings;
import com.springboot.backend.ingestion.core.IngestionOrchestrator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IngestionScheduleTests {
    IngestionSettings settings = mock(IngestionSettings.class);
    RunStore store = mock(RunStore.class);
    IngestionOrchestrator runner = mock(IngestionOrchestrator.class);
    ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);
    Clock clock = Clock.fixed(Instant.parse("2026-01-05T00:00:00Z"), ZoneOffset.UTC);
    IngestionSchedule schedule;

    @BeforeEach void setup() {
        schedule = new IngestionSchedule(settings, store, runner, scheduler, clock);
    }

    @Test void disabledSchedulingRecoversOnceAndDoesNotArmRepeatedPolling() {
        when(settings.schedulingEnabled()).thenReturn(false);
        when(store.state()).thenReturn(new CoordinatorState());
        schedule.reconcile();
        verify(runner).kick();
        verify(store).state();
        verifyNoInteractions(scheduler);
    }

    @Test void schedulesOnlyAtPersistedDueTime() {
        when(settings.schedulingEnabled()).thenReturn(true);
        var state = new CoordinatorState();
        state.nextDue = Instant.parse("2026-01-06T00:00:00Z");
        when(store.state()).thenReturn(state);
        schedule.reconcile();
        verify(scheduler).schedule(any(Runnable.class), eq(state.nextDue));
    }

    @Test void overdueSlotRunsAndRearmsAtUpdatedDueTime() {
        when(settings.schedulingEnabled()).thenReturn(true);
        var overdue = new CoordinatorState();
        overdue.nextDue = clock.instant().minusSeconds(30);
        var next = new CoordinatorState();
        next.nextDue = clock.instant().plusSeconds(86400);
        when(store.state()).thenReturn(overdue, overdue, next);
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        schedule.reconcile();
        verify(scheduler).schedule(callback.capture(), eq(clock.instant()));
        callback.getValue().run();
        verify(runner).scheduled();
        verify(scheduler).schedule(any(Runnable.class), eq(next.nextDue));
    }

    @Test void activeRunIsCheckedAtLeaseExpiryRatherThanEveryTenSeconds() {
        var state = new CoordinatorState();
        state.activeRunId = "pending";
        state.leaseUntil = clock.instant().plusSeconds(90);
        when(store.state()).thenReturn(state);
        schedule.reconcile();
        verify(scheduler).schedule(any(Runnable.class), eq(state.leaseUntil.plusSeconds(1)));
    }

    @Test void dueTimerDoesNotAdmitScheduledRunWhileManualRunIsActive() {
        when(settings.schedulingEnabled()).thenReturn(true);
        var state = new CoordinatorState();
        state.nextDue = clock.instant().minusSeconds(60);
        state.activeRunId = "manual";
        state.leaseUntil = clock.instant().plusSeconds(90);
        when(store.state()).thenReturn(state);
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        schedule.reconcile();
        verify(scheduler).schedule(callback.capture(), eq(state.leaseUntil.plusSeconds(1)));
        callback.getValue().run();
        verify(runner, never()).scheduled();
        verify(runner, times(2)).kick();
    }

    @Test void databaseOutageSchedulesBoundedRetry() {
        when(store.state()).thenThrow(new IllegalStateException("database unavailable"));
        assertDoesNotThrow(schedule::reconcile);
        verify(scheduler).schedule(any(Runnable.class), eq(clock.instant().plusSeconds(600)));
    }

    @Test void scheduledFailureRetriesRatherThanLosingTheTimer() {
        when(settings.schedulingEnabled()).thenReturn(true);
        var state = new CoordinatorState();
        state.nextDue = clock.instant();
        when(store.state()).thenReturn(state);
        doThrow(new IllegalStateException("scheduler failed")).when(runner).scheduled();
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        schedule.reconcile();
        verify(scheduler).schedule(callback.capture(), eq(clock.instant()));
        assertDoesNotThrow(() -> callback.getValue().run());
        verify(scheduler).schedule(any(Runnable.class), eq(clock.instant().plusSeconds(600)));
    }

    @Test void shutdownCancelsPendingTimer() {
        var state = new CoordinatorState();
        state.nextDue = clock.instant().plusSeconds(86400);
        when(settings.schedulingEnabled()).thenReturn(true);
        when(store.state()).thenReturn(state);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenReturn((ScheduledFuture) future);
        schedule.reconcile();
        schedule.close();
        verify(future).cancel(false);
    }
}
