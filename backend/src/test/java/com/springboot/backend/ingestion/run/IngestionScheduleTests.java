package com.springboot.backend.ingestion.run;

import com.springboot.backend.ingestion.config.IngestionSettings;
import com.springboot.backend.ingestion.core.IngestionOrchestrator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Direct unit tests of IngestionSchedule.reconcile() - a @Scheduled method, so it's called
 * directly rather than waited for. Its collaborators are mocked; both of its own try/catch
 * blocks (kick()/state() failing, and the scheduled task's runner.scheduled() failing) are
 * exercised explicitly, since a swallowed exception is otherwise invisible to a test that
 * only checks the happy path.
 */
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

    @Test void alwaysKicksTheOrchestratorEvenWhenSchedulingIsDisabled() {
        when(settings.schedulingEnabled()).thenReturn(false);
        schedule.reconcile();
        verify(runner).kick();
        verifyNoInteractions(scheduler);
    }

    @Test void armsTheNextRunAtNextDueWhenThatIsStillInTheFuture() {
        when(settings.schedulingEnabled()).thenReturn(true);
        var state = new CoordinatorState();
        state.nextDue = Instant.parse("2026-01-06T00:00:00Z");
        when(store.state()).thenReturn(state);

        schedule.reconcile();

        verify(scheduler).schedule(any(Runnable.class), eq(state.nextDue));
    }

    @Test void armsImmediatelyWhenNextDueHasAlreadyPassed() {
        when(settings.schedulingEnabled()).thenReturn(true);
        var state = new CoordinatorState();
        state.nextDue = Instant.parse("2020-01-01T00:00:00Z"); // long past clock's fixed instant
        when(store.state()).thenReturn(state);

        schedule.reconcile();

        verify(scheduler).schedule(any(Runnable.class), eq(clock.instant()));
    }

    @SuppressWarnings("unchecked")
    @Test void cancelsThePreviouslyArmedTaskBeforeArmingTheNextOne() {
        when(settings.schedulingEnabled()).thenReturn(true);
        var state = new CoordinatorState();
        state.nextDue = Instant.parse("2026-01-06T00:00:00Z");
        when(store.state()).thenReturn(state);
        ScheduledFuture<Object> first = mock(ScheduledFuture.class);
        ScheduledFuture<Object> second = mock(ScheduledFuture.class);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenReturn((ScheduledFuture) first, (ScheduledFuture) second);

        schedule.reconcile();
        schedule.reconcile();

        verify(first).cancel(false);
        verifyNoInteractions(second);
    }

    @Test void aFailureFetchingCoordinatorStateIsCaughtRatherThanThrown() {
        when(settings.schedulingEnabled()).thenReturn(true);
        when(store.state()).thenThrow(new RuntimeException("store unavailable"));
        assertDoesNotThrow(schedule::reconcile);
    }

    @Test void aFailureKickingTheOrchestratorIsCaughtRatherThanThrown() {
        doThrow(new RuntimeException("kick failed")).when(runner).kick();
        assertDoesNotThrow(schedule::reconcile);
        verifyNoInteractions(scheduler);
    }

    @SuppressWarnings("unchecked")
    @Test void theScheduledTaskItselfSwallowsAFailureFromTheOrchestrator() {
        when(settings.schedulingEnabled()).thenReturn(true);
        var state = new CoordinatorState();
        state.nextDue = Instant.parse("2026-01-06T00:00:00Z");
        when(store.state()).thenReturn(state);
        var captor = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        doThrow(new RuntimeException("scheduled run failed")).when(runner).scheduled();

        schedule.reconcile();
        verify(scheduler).schedule(captor.capture(), any(Instant.class));

        assertDoesNotThrow(() -> captor.getValue().run());
        verify(runner).scheduled();
    }
}
