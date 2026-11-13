package com.springboot.backend.recommendation.trigger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.springboot.backend.exception.ResourceNotFoundException;
import com.springboot.backend.marketevent.MarketEvent;
import com.springboot.backend.marketevent.MarketEventService;
import com.springboot.backend.marketevent.MarketEventType;
import com.springboot.backend.model.Product;
import com.springboot.backend.recommendation.AiServiceException;
import com.springboot.backend.recommendation.AssessRequest;
import com.springboot.backend.recommendation.CandidateEvaluation;
import com.springboot.backend.recommendation.DeterministicRecommendationService;
import com.springboot.backend.recommendation.DeterministicRecommendationService.PersistedEvaluation;
import com.springboot.backend.recommendation.classification.TierMapper;
import com.springboot.backend.recommendation.classification.UpgradeClassification;
import com.springboot.backend.repository.CandidateProduct;
import com.springboot.backend.repository.ProductRepository;
import com.springboot.backend.repository.UserDeviceRepository;
import com.springboot.backend.recommendation.trigger.TriggerRun.PairOutcome;
import com.springboot.backend.recommendation.trigger.TriggerRun.Status;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * Trigger selection, the early exit, per-pair isolation and the run status,
 * with every collaborator mocked. The real database path is covered by
 * {@code RecommendationTriggerIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class RecommendationTriggerServiceTest {

    private static final long EVENT_ID = 42L;
    private static final long PRODUCT_ID = 17L;
    private static final long USER_ID = 5L;

    @Mock MarketEventService marketEvents;
    @Mock ProductRepository products;
    @Mock UserDeviceRepository devices;
    @Mock AffectedDeviceSelector selector;
    @Mock DeterministicRecommendationService deterministic;
    @Mock AiAssessmentStep aiStep;
    @Mock TriggerRunLog runLog;

    private RecommendationTriggerService service;

    @BeforeEach
    void setUp() {
        service = new RecommendationTriggerService(
                marketEvents, products, devices, selector, deterministic, aiStep, runLog, new SyncTaskExecutor());
    }

    // --- Trigger 1: market event -------------------------------------------

    @Test
    void anEventWithoutAProductIsSkippedAndStillLogged() {
        when(marketEvents.find(EVENT_ID)).thenReturn(Optional.of(event(null)));

        TriggerRun run = service.runForMarketEvent(EVENT_ID, "TEST");

        assertEquals(Status.SKIPPED, run.status());
        assertEquals("event names no product", run.metadata().get("skipped_reason"));
        verifyNoInteractions(selector, deterministic, aiStep);
        verify(runLog).write(run);
    }

    @Test
    void anEventForAMissingProductIsSkipped() {
        when(marketEvents.find(EVENT_ID)).thenReturn(Optional.of(event(PRODUCT_ID)));
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        TriggerRun run = service.runForMarketEvent(EVENT_ID, "TEST");

        assertEquals(Status.SKIPPED, run.status());
        verifyNoInteractions(selector, deterministic, aiStep);
        verify(runLog).write(run);
    }

    @Test
    void anUnknownEventIsSkipped() {
        when(marketEvents.find(EVENT_ID)).thenReturn(Optional.empty());

        assertEquals(Status.SKIPPED, service.runForMarketEvent(EVENT_ID, "TEST").status());
        verify(runLog).write(any());
    }

    @Test
    void everySelectedDeviceIsPairedWithTheEventProductAndLinkedToTheEvent() {
        givenEventForSmartphone();
        when(selector.forProduct("SMARTPHONE", PRODUCT_ID))
                .thenReturn(new AffectedDeviceSelector.Selection(List.of(1L, 2L, 3L), List.of(9L)));
        doReturn(classified(1L, TierMapper.STRONG_UPGRADE_CANDIDATE)).when(deterministic).evaluatePairAndPersist(1L, PRODUCT_ID, EVENT_ID);
        doReturn(classified(2L, TierMapper.NO_MEANINGFUL_CHANGE)).when(deterministic).evaluatePairAndPersist(2L, PRODUCT_ID, EVENT_ID);
        doReturn(notShortlisted(3L)).when(deterministic).evaluatePairAndPersist(3L, PRODUCT_ID, EVENT_ID);
        when(aiStep.assess(any(), any(), eq(EVENT_ID), any())).thenReturn(AiAssessmentStep.Outcome.assessed(100L));

        TriggerRun run = service.runForMarketEvent(EVENT_ID, "TEST");

        assertEquals(Status.SUCCESS, run.status());
        assertEquals(3, run.metadata().get("pairs_selected"));
        assertEquals(1, run.metadata().get("devices_without_preferences"));
        assertEquals(1, run.count(PairOutcome.ASSESSED));
        assertEquals(1, run.count(PairOutcome.REJECTED_EARLY));
        assertEquals(1, run.count(PairOutcome.NOT_SHORTLISTED));

        // The trigger event reaches the AI request, for the one pair past the gate only.
        ArgumentCaptor<CandidateEvaluation> evaluation = ArgumentCaptor.forClass(CandidateEvaluation.class);
        ArgumentCaptor<AssessRequest.TriggerEvent> triggerEvent = ArgumentCaptor.forClass(AssessRequest.TriggerEvent.class);
        verify(aiStep, times(1)).assess(evaluation.capture(), any(), eq(EVENT_ID), triggerEvent.capture());
        assertEquals(1L, evaluation.getValue().userDeviceId());
        assertEquals("PRICE_CHANGE", triggerEvent.getValue().eventType());
        assertEquals(Map.of("price", 1099), triggerEvent.getValue().newValue());
        verify(runLog).write(run);
    }

    @Test
    void oneDeviceFailingDoesNotStopTheOthers() {
        givenEventForSmartphone();
        when(selector.forProduct("SMARTPHONE", PRODUCT_ID))
                .thenReturn(new AffectedDeviceSelector.Selection(List.of(1L, 2L), List.of()));
        when(deterministic.evaluatePairAndPersist(1L, PRODUCT_ID, EVENT_ID))
                .thenThrow(new ResourceNotFoundException("No specifications recorded for owned product 3"));
        doReturn(classified(2L, TierMapper.NO_MEANINGFUL_CHANGE)).when(deterministic).evaluatePairAndPersist(2L, PRODUCT_ID, EVENT_ID);

        TriggerRun run = service.runForMarketEvent(EVENT_ID, "TEST");

        assertEquals(Status.PARTIAL_SUCCESS, run.status());
        assertEquals(1, run.count(PairOutcome.FAILED));
        assertEquals(1, run.count(PairOutcome.REJECTED_EARLY));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failures = (List<Map<String, Object>>) run.metadata().get("failures");
        assertEquals(1L, failures.getFirst().get("user_device_id"));
        assertEquals("DETERMINISTIC", failures.getFirst().get("stage"));
    }

    @Test
    void anAiFailureIsRecordedAndTheBatchCarriesOn() {
        givenEventForSmartphone();
        when(selector.forProduct("SMARTPHONE", PRODUCT_ID))
                .thenReturn(new AffectedDeviceSelector.Selection(List.of(1L, 2L), List.of()));
        doReturn(classified(1L, TierMapper.WORTH_CONSIDERING)).when(deterministic).evaluatePairAndPersist(1L, PRODUCT_ID, EVENT_ID);
        doReturn(classified(2L, TierMapper.WORTH_CONSIDERING)).when(deterministic).evaluatePairAndPersist(2L, PRODUCT_ID, EVENT_ID);
        when(aiStep.assess(argThat(e -> e != null && e.userDeviceId() == 1L), any(), any(), any()))
                .thenReturn(AiAssessmentStep.Outcome.failed(new AiServiceException("AI service timeout", null)));
        when(aiStep.assess(argThat(e -> e != null && e.userDeviceId() == 2L), any(), any(), any()))
                .thenReturn(AiAssessmentStep.Outcome.assessed(200L));

        TriggerRun run = service.runForMarketEvent(EVENT_ID, "TEST");

        assertEquals(Status.PARTIAL_SUCCESS, run.status());
        assertEquals(1, run.count(PairOutcome.ASSESSED));
        assertEquals(1, run.count(PairOutcome.FAILED));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failures = (List<Map<String, Object>>) run.metadata().get("failures");
        assertEquals("AI", failures.getFirst().get("stage"));
        assertTrue(failures.getFirst().get("error").toString().contains("AI service timeout"));
    }

    @Test
    void everyPairFailingIsAFailure() {
        givenEventForSmartphone();
        when(selector.forProduct("SMARTPHONE", PRODUCT_ID))
                .thenReturn(new AffectedDeviceSelector.Selection(List.of(1L), List.of()));
        when(deterministic.evaluatePairAndPersist(1L, PRODUCT_ID, EVENT_ID)).thenThrow(new IllegalStateException("db down"));

        assertEquals(Status.FAILURE, service.runForMarketEvent(EVENT_ID, "TEST").status());
    }

    @Test
    void anAiSkipIsCountedWithItsReason() {
        givenEventForSmartphone();
        when(selector.forProduct("SMARTPHONE", PRODUCT_ID))
                .thenReturn(new AffectedDeviceSelector.Selection(List.of(1L), List.of()));
        doReturn(classified(1L, TierMapper.WORTH_WATCHING)).when(deterministic).evaluatePairAndPersist(1L, PRODUCT_ID, EVENT_ID);
        when(aiStep.assess(any(), any(), any(), any()))
                .thenReturn(AiAssessmentStep.Outcome.skipped("missing user_devices.condition"));

        TriggerRun run = service.runForMarketEvent(EVENT_ID, "TEST");

        assertEquals(Status.SUCCESS, run.status());
        assertEquals(1, run.count(PairOutcome.AI_SKIPPED));
        assertEquals(1, run.metadata().get("pairs_succeeded"));
    }

    // --- Trigger 2: device inventory --------------------------------------

    @Test
    void aDeviceThatCannotBeEvaluatedIsSkipped() {
        when(devices.isEvaluable(42L)).thenReturn(false);

        TriggerRun run = service.runForDevice(42L, "DEVICE_ADDED");

        assertEquals(Status.SKIPPED, run.status());
        verifyNoInteractions(deterministic, aiStep);
        verify(runLog).write(run);
    }

    @Test
    void theWholeShortlistIsEvaluatedWithNoEventLinkAndOnlyGatePassersReachTheAi() {
        when(devices.isEvaluable(42L)).thenReturn(true);
        CandidateEvaluation evaluation = new CandidateEvaluation(42L, USER_ID,
                List.of(candidate(30L, TierMapper.STRONG_UPGRADE_CANDIDATE), candidate(31L, TierMapper.NO_MEANINGFUL_CHANGE)),
                List.of(new CandidateEvaluation.Skipped(product(32L), "no specs")));
        when(deterministic.evaluateAndPersist(42L)).thenReturn(new PersistedEvaluation(evaluation, 2, 1));
        when(aiStep.assess(any(), any(), isNull(), isNull())).thenReturn(AiAssessmentStep.Outcome.assessed(300L));

        TriggerRun run = service.runForDevice(42L, "DEVICE_ADDED");

        assertEquals(Status.SUCCESS, run.status());
        assertEquals(3, run.metadata().get("pairs_selected"));
        assertEquals(1, run.metadata().get("rows_deleted"));
        assertEquals(1, run.count(PairOutcome.ASSESSED));
        assertEquals(1, run.count(PairOutcome.REJECTED_EARLY));
        assertEquals(1, run.count(PairOutcome.UNCLASSIFIABLE));

        ArgumentCaptor<CandidateEvaluation.Classified> assessed = ArgumentCaptor.forClass(CandidateEvaluation.Classified.class);
        verify(aiStep, times(1)).assess(eq(evaluation), assessed.capture(), isNull(), isNull());
        assertEquals(30L, assessed.getValue().candidate().getProductId());
    }

    @Test
    void aDeviceWhoseEvaluationFailsIsLoggedAsAFailure() {
        when(devices.isEvaluable(42L)).thenReturn(true);
        when(deterministic.evaluateAndPersist(42L)).thenThrow(new ResourceNotFoundException("no specs"));

        TriggerRun run = service.runForDevice(42L, "DEVICE_UPDATED");

        assertEquals(Status.FAILURE, run.status());
        assertEquals("DEVICE_UPDATED", run.metadata().get("cause"));
        verify(runLog).write(run);
    }

    @Test
    void aLoggingFailureDoesNotEscape() {
        when(devices.isEvaluable(42L)).thenReturn(false);
        doThrow(new IllegalStateException("system_log unavailable")).when(runLog).write(any());

        assertDoesNotThrow(() -> service.runForDevice(42L, "DEVICE_ADDED"));
    }

    // --- Manual re-fire ----------------------------------------------------

    @Test
    void reFiringAnUnknownTargetIsRejectedBeforeQueueing() {
        when(marketEvents.find(EVENT_ID)).thenReturn(Optional.empty());
        when(devices.existsById(42L)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> service.queueMarketEvent(EVENT_ID));
        assertThrows(ResourceNotFoundException.class, () -> service.queueDevice(42L));
        verifyNoInteractions(runLog);
    }

    @Test
    void reFiringAKnownDeviceRunsTheTriggerOnTheExecutor() {
        when(devices.existsById(42L)).thenReturn(true);
        when(devices.isEvaluable(42L)).thenReturn(false);

        service.queueDevice(42L);

        ArgumentCaptor<TriggerRun> run = ArgumentCaptor.forClass(TriggerRun.class);
        verify(runLog).write(run.capture());
        assertEquals(RecommendationTriggerService.CAUSE_ADMIN, run.getValue().metadata().get("cause"));
    }

    // --- fixtures -----------------------------------------------------------

    private void givenEventForSmartphone() {
        when(marketEvents.find(EVENT_ID)).thenReturn(Optional.of(event(PRODUCT_ID)));
        Product product = new Product("Samsung", "Galaxy S26", Product.CATEGORY_SMARTPHONE, Product.STATUS_VERIFIED);
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
    }

    private static MarketEvent event(Long productId) {
        return new MarketEvent(EVENT_ID, productId, MarketEventType.PRICE_CHANGE, "Galaxy S26 drops to S$1099",
                null, Map.of("price", 1199), Map.of("price", 1099), "admin", OffsetDateTime.now());
    }

    private static PersistedEvaluation classified(long deviceId, String verdict) {
        return new PersistedEvaluation(
                new CandidateEvaluation(deviceId, USER_ID, List.of(candidate(PRODUCT_ID, verdict)), List.of()), 1, 0);
    }

    private static PersistedEvaluation notShortlisted(long deviceId) {
        return new PersistedEvaluation(new CandidateEvaluation(deviceId, USER_ID, List.of(), List.of()), 0, 0);
    }

    private static CandidateEvaluation.Classified candidate(long productId, String verdict) {
        UpgradeClassification classification = mock(UpgradeClassification.class);
        lenient().when(classification.verdict()).thenReturn(verdict);
        return new CandidateEvaluation.Classified(product(productId), classification);
    }

    private static CandidateProduct product(long productId) {
        CandidateProduct product = mock(CandidateProduct.class);
        lenient().when(product.getProductId()).thenReturn(productId);
        return product;
    }
}
