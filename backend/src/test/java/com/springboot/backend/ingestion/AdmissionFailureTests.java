package com.springboot.backend.ingestion;

import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class AdmissionFailureTests {
    @Test void databaseFailurePreventsAnySourceExecution() {
        var store = mock(RunStore.class);
        var source = mock(IngestionSource.class);
        when(source.sourceId()).thenReturn("source");
        when(source.cooldown()).thenReturn(java.time.Duration.ofMinutes(15));
        var registry = new SourceRegistry(List.of(source), new IngestionSettings(false, null, List.of("source")));
        when(store.admit(anyList(), anyString(), anyString(), isNull(), eq(false), eq(false)))
                .thenThrow(new DataAccessResourceFailureException("offline"));
        var runner = new IngestionOrchestrator(store, registry, List.of(), Clock.systemUTC(), new ThreadPoolTaskScheduler());
        try {
            assertThrows(DataAccessResourceFailureException.class, () -> runner.manual(List.of("source"), "admin", "failure-key", null));
            verify(store, never()).claim(anyString());
            verify(source, never()).ingest(any(), any());
        } catch (Exception e) { throw new AssertionError(e); }
        finally { runner.close(); }
    }
    @Test void closedContextStopsFurtherRequests() {
        var context = new SourceContext(Clock.systemUTC(), () -> {});
        context.close();
        assertThrows(IllegalStateException.class, context::check);
    }
}
