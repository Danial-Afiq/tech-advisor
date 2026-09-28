package com.springboot.backend.ingestion.core;

import com.springboot.backend.ingestion.run.RunLog;
import com.springboot.backend.ingestion.run.RunStore;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Direct unit tests of IngestionOrchestrator's request-shaping logic (idempotency-key/reason
 * validation in manual(), and scheduled()'s admission of a fresh scheduler-driven run) - with
 * every collaborator mocked, so store.claim() returns null and kick()'s worker never actually
 * executes a run. Real execution (payload processing, cooldowns, retry, the source worker
 * thread) is covered by IngestionIntegrationTests against a real database.
 */
class IngestionOrchestratorUnitTests {
    RunStore store = mock(RunStore.class);
    SourceRegistry registry = mock(SourceRegistry.class);
    Clock clock = Clock.systemUTC();
    ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);
    IngestionOrchestrator orchestrator;

    @BeforeEach void setup() {
        orchestrator = new IngestionOrchestrator(store, registry, List.of(), clock, scheduler);
    }

    @Test void manualRejectsAnIdempotencyKeyThatIsTooShort() {
        assertThrows(IllegalArgumentException.class, () -> orchestrator.manual(List.of("a"), "admin", "short", null));
        verifyNoInteractions(registry, store);
    }

    @Test void manualRejectsAReasonLongerThan500Characters() {
        assertThrows(IllegalArgumentException.class,
                () -> orchestrator.manual(List.of("a"), "admin", "a-valid-key-1234", "x".repeat(501)));
        verifyNoInteractions(registry, store);
    }

    @Test void manualAdmitsUsingWhateverTheRegistrySelects() {
        var source = mock(IngestionSource.class);
        when(source.simulation()).thenReturn(false);
        when(registry.select(List.of("a"))).thenReturn(List.of("a"));
        when(registry.get("a")).thenReturn(source);
        var run = new RunLog();
        when(store.admit(List.of("a"), "admin", "a-valid-key-1234", "reason", false, false, null)).thenReturn(run);

        assertSame(run, orchestrator.manual(List.of("a"), "admin", "a-valid-key-1234", "reason"));
    }

    @Test void scheduledSelectsFromTheRegistryAndAdmitsAnUnattendedRun() {
        var simulated = mock(IngestionSource.class);
        when(simulated.simulation()).thenReturn(true);
        when(registry.select(null)).thenReturn(List.of("simulated-release"));
        when(registry.get("simulated-release")).thenReturn(simulated);

        orchestrator.scheduled();

        verify(store).admit(List.of("simulated-release"), "scheduler", null, null, true, true);
    }

    @Test void scheduledMarksTheRunAsNonSimulationWhenAnyRealSourceIsIncluded() {
        var real = mock(IngestionSource.class);
        when(real.simulation()).thenReturn(false);
        when(registry.select(null)).thenReturn(List.of("mobileapi-smartphone"));
        when(registry.get("mobileapi-smartphone")).thenReturn(real);

        orchestrator.scheduled();

        verify(store).admit(List.of("mobileapi-smartphone"), "scheduler", null, null, true, false);
    }
}
