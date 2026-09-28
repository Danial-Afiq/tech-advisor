package com.springboot.backend.ingestion.api;

import com.springboot.backend.ingestion.config.IngestionSettings;
import com.springboot.backend.ingestion.core.IngestionFailure;
import com.springboot.backend.ingestion.core.IngestionOrchestrator;
import com.springboot.backend.ingestion.core.IngestionSource;
import com.springboot.backend.ingestion.core.SourceContext;
import com.springboot.backend.ingestion.core.SourceRegistry;
import com.springboot.backend.ingestion.run.CoordinatorState;
import com.springboot.backend.ingestion.run.RunLog;
import com.springboot.backend.ingestion.run.RunStore;
import com.springboot.backend.ingestion.searchapi.ProductMatcher;
import com.springboot.backend.ingestion.searchapi.SearchApiRepository;
import com.springboot.backend.ingestion.searchapi.SearchApiSource;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Direct unit tests of IngestionController - no Spring context, no DB, no network. The
 * controller's own logic (candidate/run request validation, the read-only reporting
 * endpoints, and the exception handlers) is plain Java over its injected collaborators,
 * so it's exercised by calling the methods directly and mocking those collaborators,
 * rather than through MockMvc. HTTP-level behaviour (routing, security, JSON) is already
 * covered by ManualProductIngestionTests and IngestionIntegrationTests.
 */
class IngestionControllerTests {
    IngestionOrchestrator runner = mock(IngestionOrchestrator.class);
    RunStore store = mock(RunStore.class);
    SourceRegistry registry = mock(SourceRegistry.class);
    IngestionSettings settings = mock(IngestionSettings.class);
    SearchApiRepository products = mock(SearchApiRepository.class);
    SearchApiSource searchApi = mock(SearchApiSource.class);
    Clock clock = Clock.systemUTC();
    IngestionController controller;

    @BeforeEach void setup() {
        controller = new IngestionController(runner, store, registry, settings, products, searchApi, clock);
    }

    private static SourceContext.RetryLater retryLater() throws Exception {
        var ctor = SourceContext.RetryLater.class.getDeclaredConstructor(Instant.class);
        ctor.setAccessible(true);
        return ctor.newInstance(Instant.now().plusSeconds(30));
    }
    private static SourceContext.TransportFailure transportFailure() throws Exception {
        var ctor = SourceContext.TransportFailure.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    @Test void candidatesRejectsWhenSearchApiIsDisabled() {
        when(registry.enabled(SearchApiSource.ID)).thenReturn(false);
        var rejected = assertThrows(ResponseStatusException.class,
                () -> controller.candidates(new IngestionController.CandidateRequest("Apple iPhone 16")));
        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
        verifyNoInteractions(searchApi);
    }

    @Test void candidatesRejectsAMalformedNameBeforeCallingSearchApi() {
        when(registry.enabled(SearchApiSource.ID)).thenReturn(true);
        var rejected = assertThrows(ResponseStatusException.class,
                () -> controller.candidates(new IngestionController.CandidateRequest("Unknown")));
        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
        verifyNoInteractions(searchApi);
    }

    @Test void candidatesRejectsWhenSearchApiFindsNothing() throws Exception {
        when(registry.enabled(SearchApiSource.ID)).thenReturn(true);
        when(searchApi.findCandidates(any(), any())).thenReturn(List.of());
        var rejected = assertThrows(ResponseStatusException.class,
                () -> controller.candidates(new IngestionController.CandidateRequest("Apple iPhone 16")));
        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
    }

    @Test void candidatesReturnsWhatSearchApiFinds() throws Exception {
        when(registry.enabled(SearchApiSource.ID)).thenReturn(true);
        var expected = List.of(new ProductMatcher.Candidate("ext-1", "Apple iPhone 16"));
        when(searchApi.findCandidates(any(), any())).thenReturn(expected);
        assertEquals(expected, controller.candidates(new IngestionController.CandidateRequest("Apple iPhone 16")));
    }

    @Test void candidatesMapsRateLimitToTooManyRequests() throws Exception {
        when(registry.enabled(SearchApiSource.ID)).thenReturn(true);
        when(searchApi.findCandidates(any(), any())).thenThrow(retryLater());
        var rejected = assertThrows(ResponseStatusException.class,
                () -> controller.candidates(new IngestionController.CandidateRequest("Apple iPhone 16")));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.getStatusCode());
    }

    @Test void candidatesMapsTransportFailureToServiceUnavailable() throws Exception {
        when(registry.enabled(SearchApiSource.ID)).thenReturn(true);
        when(searchApi.findCandidates(any(), any())).thenThrow(transportFailure());
        var rejected = assertThrows(ResponseStatusException.class,
                () -> controller.candidates(new IngestionController.CandidateRequest("Apple iPhone 16")));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, rejected.getStatusCode());
    }

    @Test void candidatesMapsIngestionFailureToBadGateway() throws Exception {
        when(registry.enabled(SearchApiSource.ID)).thenReturn(true);
        when(searchApi.findCandidates(any(), any()))
                .thenThrow(new IngestionFailure(IngestionFailure.Code.SEARCHAPI_NO_MATCH));
        var rejected = assertThrows(ResponseStatusException.class,
                () -> controller.candidates(new IngestionController.CandidateRequest("Apple iPhone 16")));
        assertEquals(HttpStatus.BAD_GATEWAY, rejected.getStatusCode());
    }

    @Test void candidatesMapsAnyOtherFailureToBadGateway() throws Exception {
        when(registry.enabled(SearchApiSource.ID)).thenReturn(true);
        when(searchApi.findCandidates(any(), any())).thenThrow(new RuntimeException("boom"));
        var rejected = assertThrows(ResponseStatusException.class,
                () -> controller.candidates(new IngestionController.CandidateRequest("Apple iPhone 16")));
        assertEquals(HttpStatus.BAD_GATEWAY, rejected.getStatusCode());
    }

    @Test void candidatesPropagatesAResponseStatusExceptionUnchanged() throws Exception {
        when(registry.enabled(SearchApiSource.ID)).thenReturn(true);
        var original = new ResponseStatusException(HttpStatus.I_AM_A_TEAPOT, "unexpected");
        when(searchApi.findCandidates(any(), any())).thenThrow(original);
        var thrown = assertThrows(ResponseStatusException.class,
                () -> controller.candidates(new IngestionController.CandidateRequest("Apple iPhone 16")));
        assertSame(original, thrown);
    }

    @Test void startRejectsASelectedProductWithNoProductName() {
        when(registry.select(any())).thenReturn(List.of(SearchApiSource.ID));
        var request = new IngestionController.Request(List.of(SearchApiSource.ID), null, null, "ext-1");
        var rejected = assertThrows(ResponseStatusException.class,
                () -> controller.start(request, "key-1", () -> "admin"));
        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
        verifyNoInteractions(runner);
    }

    @Test void startAdmitsAnUntargetedRunAndReturnsItsLocation() {
        when(registry.select(any())).thenReturn(List.of("mobileapi-smartphone"));
        var run = new RunLog(); run.runId = "run-1";
        when(runner.manual(List.of("mobileapi-smartphone"), "admin", "key-1", "manual check", null)).thenReturn(run);
        var request = new IngestionController.Request(List.of("mobileapi-smartphone"), "manual check", null, null);
        var response = controller.start(request, "key-1", () -> "admin");
        assertEquals(202, response.getStatusCode().value());
        assertEquals("/api/admin/ingestion/runs/run-1", response.getHeaders().getLocation().toString());
        assertSame(run, response.getBody());
    }

    @Test void getReturnsWhateverTheStoreHasForThatRunId() {
        var run = new RunLog(); run.runId = "run-1";
        when(store.get("run-1")).thenReturn(run);
        assertSame(run, controller.get("run-1"));
    }

    @Test void historyDelegatesPagingAndFilteringToTheStore() {
        var runs = List.of(new RunLog());
        when(store.history(1, 5, "FAILED", "MANUAL")).thenReturn(runs);
        assertSame(runs, controller.history(1, 5, "FAILED", "MANUAL"));
    }

    @Test void scheduleReportsTheCoordinatorStateAndTheConfiguredInterval() {
        when(settings.schedulingEnabled()).thenReturn(true);
        var state = new CoordinatorState();
        state.anchor = Instant.parse("2026-01-01T00:00:00Z");
        state.nextDue = Instant.parse("2026-01-03T00:00:00Z");
        state.activeRunId = "run-9";
        when(store.state()).thenReturn(state);

        var response = controller.schedule();

        assertEquals(Map.of("enabled", true, "intervalDays", RunStore.INTERVAL.toDays(),
                "intervalHours", RunStore.INTERVAL.toHours(), "anchor", state.anchor,
                "nextScheduledAt", state.nextDue, "activeRunId", "run-9"), response);
    }

    @Test void sourcesListsEachRegisteredSourceWithItsRuntimeState() {
        var state = new CoordinatorState();
        state.nextAllowed.put("mobileapi-smartphone", Instant.parse("2026-02-01T00:00:00Z"));
        when(store.state()).thenReturn(state);
        var mobileApi = mock(IngestionSource.class);
        when(mobileApi.sourceId()).thenReturn("mobileapi-smartphone");
        when(mobileApi.simulation()).thenReturn(false);
        var simulated = mock(IngestionSource.class);
        when(simulated.sourceId()).thenReturn("simulated-release");
        when(simulated.simulation()).thenReturn(true);
        when(registry.all()).thenReturn(List.of(mobileApi, simulated));
        when(registry.enabled("mobileapi-smartphone")).thenReturn(true);
        when(registry.enabled("simulated-release")).thenReturn(false);

        var rows = controller.sources();

        var mobileApiRow = new java.util.LinkedHashMap<String, Object>();
        mobileApiRow.put("sourceId", "mobileapi-smartphone"); mobileApiRow.put("enabled", true);
        mobileApiRow.put("simulation", false); mobileApiRow.put("nextAllowedAt", state.nextAllowed.get("mobileapi-smartphone"));
        var simulatedRow = new java.util.LinkedHashMap<String, Object>();
        simulatedRow.put("sourceId", "simulated-release"); simulatedRow.put("enabled", false);
        simulatedRow.put("simulation", true); simulatedRow.put("nextAllowedAt", null);
        assertEquals(List.of(mobileApiRow, simulatedRow), rows);
    }

    @Test void unavailableRespondsWith503AndAClearMessage() {
        var response = controller.unavailable();
        assertEquals(503, response.getStatusCode().value());
        assertEquals("Ingestion storage unavailable; no new run was admitted", response.getBody().get("message"));
    }
}
