package com.springboot.backend.recommendation.trigger;

import com.springboot.backend.exception.ResourceNotFoundException;
import com.springboot.backend.marketevent.MarketEvent;
import com.springboot.backend.marketevent.MarketEventService;
import com.springboot.backend.model.Product;
import com.springboot.backend.recommendation.AssessRequest;
import com.springboot.backend.recommendation.CandidateEvaluation;
import com.springboot.backend.recommendation.DeterministicRecommendationService;
import com.springboot.backend.recommendation.DeterministicRecommendationService.PersistedEvaluation;
import com.springboot.backend.recommendation.classification.TierMapper;
import com.springboot.backend.repository.ProductRepository;
import com.springboot.backend.repository.UserDeviceRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * Decides who a change affects and runs the recommendation pipeline for them.
 * The head of the pipeline AGENTS.md §18.10/§18.11 left to this ticket.
 *
 * <ul>
 *   <li><b>Market event</b> ({@link #runForMarketEvent}): one pair per current
 *       device in the event product's category, each through
 *       {@link DeterministicRecommendationService#evaluatePairAndPersist} with
 *       {@code trigger_event_id} set.</li>
 *   <li><b>Device inventory</b> ({@link #runForDevice}): the device's whole
 *       shortlist through {@link DeterministicRecommendationService#evaluateAndPersist},
 *       {@code trigger_event_id} null. Only that device's rows change.</li>
 * </ul>
 *
 * <p>Either way, a pair whose verdict is not {@code NO_MEANINGFUL_CHANGE} then
 * gets the AI step; one that is gets no model call (the §7.1 early exit).
 *
 * <p>Not {@code @Transactional}. Every pair commits on its own and every
 * failure is caught per pair or per device, so one bad pair neither rolls back
 * nor stops the rest. Each run ends with exactly one {@code system_log} row.
 */
@Service
public class RecommendationTriggerService {

    static final String CAUSE_MARKET_EVENT = "MARKET_EVENT_RECORDED";
    static final String CAUSE_ADMIN = "ADMIN_REFIRE";

    private static final Logger LOG = LoggerFactory.getLogger(RecommendationTriggerService.class);

    private final MarketEventService marketEvents;
    private final ProductRepository products;
    private final UserDeviceRepository devices;
    private final AffectedDeviceSelector selector;
    private final DeterministicRecommendationService deterministic;
    private final AiAssessmentStep aiStep;
    private final TriggerRunLog runLog;
    private final TaskExecutor executor;

    public RecommendationTriggerService(
            MarketEventService marketEvents,
            ProductRepository products,
            UserDeviceRepository devices,
            AffectedDeviceSelector selector,
            DeterministicRecommendationService deterministic,
            AiAssessmentStep aiStep,
            TriggerRunLog runLog,
            @Qualifier(RecommendationTriggerConfiguration.EXECUTOR) TaskExecutor executor) {

        this.marketEvents = marketEvents;
        this.products = products;
        this.devices = devices;
        this.selector = selector;
        this.deterministic = deterministic;
        this.aiStep = aiStep;
        this.runLog = runLog;
        this.executor = executor;
    }

    /**
     * Trigger 1. Skips (and logs why) when the event names no product or the
     * product no longer exists.
     */
    public TriggerRun runForMarketEvent(long marketEventId, String cause) {
        TriggerRun run = new TriggerRun(TriggerRun.Kind.MARKET_EVENT, cause).context("market_event_id", marketEventId);
        try {
            Optional<MarketEvent> found = marketEvents.find(marketEventId);
            if (found.isEmpty()) {
                run.skip("market event not found");
            } else {
                runForMarketEvent(run, found.get());
            }
        } catch (RuntimeException e) {
            run.fail(null, null, "SELECTION", e);
        }
        return finish(run);
    }

    private void runForMarketEvent(TriggerRun run, MarketEvent event) {
        run.context("event_type", event.eventType().name()).context("product_id", event.productId());
        if (event.productId() == null) {
            run.skip("event names no product");
            return;
        }

        Optional<Product> product = products.findById(event.productId());
        if (product.isEmpty()) {
            run.skip("product not found");
            return;
        }

        String category = product.get().getCategory();
        AffectedDeviceSelector.Selection selection = selector.forProduct(category, event.productId());
        run.context("category", category)
                .context("devices_selected", selection.evaluable().size())
                .context("devices_without_preferences", selection.withoutPreferences().size());
        run.selected(selection.evaluable().size());

        AssessRequest.TriggerEvent triggerEvent = new AssessRequest.TriggerEvent(
                event.eventType().name(), event.title(), event.oldValue(), event.newValue());

        for (Long deviceId : selection.evaluable()) {
            evaluatePair(run, deviceId, event.productId(), event.id(), triggerEvent);
        }
    }

    private void evaluatePair(
            TriggerRun run, Long deviceId, Long productId, Long eventId, AssessRequest.TriggerEvent triggerEvent) {
        try {
            PersistedEvaluation persisted = deterministic.evaluatePairAndPersist(deviceId, productId, eventId);
            run.rowsDeleted(persisted.deleted());
            CandidateEvaluation evaluation = persisted.evaluation();

            if (!evaluation.ranked().isEmpty()) {
                afterClassification(run, evaluation, evaluation.ranked().getFirst(), eventId, triggerEvent);
            } else if (!evaluation.skipped().isEmpty()) {
                run.record(TriggerRun.PairOutcome.UNCLASSIFIABLE);
            } else {
                run.record(TriggerRun.PairOutcome.NOT_SHORTLISTED);
            }
        } catch (RuntimeException e) {
            LOG.warn("Market event {}: device {} could not be evaluated", eventId, deviceId, e);
            run.fail(deviceId, productId, "DETERMINISTIC", e);
        }
    }

    /**
     * Trigger 2. A device that is not evaluable yet (no catalogue link, no
     * budget, no longer current) is skipped and logged.
     */
    public TriggerRun runForDevice(long userDeviceId, String cause) {
        TriggerRun run = new TriggerRun(TriggerRun.Kind.DEVICE_INVENTORY, cause).context("user_device_id", userDeviceId);
        try {
            if (!devices.isEvaluable(userDeviceId)) {
                run.skip("device not evaluable (needs to be current, linked to a catalogue product, and have a budget)");
            } else {
                PersistedEvaluation persisted = deterministic.evaluateAndPersist(userDeviceId);
                run.rowsDeleted(persisted.deleted());
                CandidateEvaluation evaluation = persisted.evaluation();
                run.selected(evaluation.ranked().size() + evaluation.skipped().size());

                evaluation.skipped().forEach(s -> run.record(TriggerRun.PairOutcome.UNCLASSIFIABLE));
                for (CandidateEvaluation.Classified classified : evaluation.ranked()) {
                    afterClassification(run, evaluation, classified, null, null);
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("Device {} could not be evaluated", userDeviceId, e);
            run.fail(userDeviceId, null, "DETERMINISTIC", e);
        }
        return finish(run);
    }

    /** The §7.1 early exit, then the AI step for whatever passed it. */
    private void afterClassification(
            TriggerRun run,
            CandidateEvaluation evaluation,
            CandidateEvaluation.Classified classified,
            Long eventId,
            AssessRequest.TriggerEvent triggerEvent) {

        if (TierMapper.NO_MEANINGFUL_CHANGE.equals(classified.classification().verdict())) {
            run.record(TriggerRun.PairOutcome.REJECTED_EARLY);
            return;
        }

        Long deviceId = evaluation.userDeviceId();
        Long productId = classified.candidate().getProductId();
        AiAssessmentStep.Outcome outcome = aiStep.assess(evaluation, classified, eventId, triggerEvent);
        switch (outcome.kind()) {
            case ASSESSED -> run.record(TriggerRun.PairOutcome.ASSESSED);
            case SKIPPED -> run.aiSkipped(deviceId, productId, outcome.reason());
            case FAILED -> {
                LOG.warn("AI assessment failed for device {} / candidate {}: {}",
                        deviceId, productId, outcome.error().getMessage());
                run.fail(deviceId, productId, "AI", outcome.error());
            }
        }
    }

    private TriggerRun finish(TriggerRun run) {
        try {
            runLog.write(run);
        } catch (RuntimeException e) {
            LOG.error("Could not write the system_log row for a {} trigger run", run.kind(), e);
        }
        LOG.info("{}", run.message());
        return run;
    }

    /**
     * Admin re-fire of Trigger 1 for an existing event. Validates, then queues on
     * the trigger executor and returns immediately.
     *
     * @throws ResourceNotFoundException when the event does not exist
     */
    public void queueMarketEvent(long marketEventId) {
        if (marketEvents.find(marketEventId).isEmpty()) {
            throw new ResourceNotFoundException("Market event " + marketEventId + " not found");
        }
        executor.execute(() -> runForMarketEvent(marketEventId, CAUSE_ADMIN));
    }

    /**
     * Admin re-fire of Trigger 2 for an existing device.
     *
     * @throws ResourceNotFoundException when the device does not exist
     */
    public void queueDevice(long userDeviceId) {
        if (!devices.existsById(userDeviceId)) {
            throw new ResourceNotFoundException("Owned device " + userDeviceId + " not found");
        }
        executor.execute(() -> runForDevice(userDeviceId, CAUSE_ADMIN));
    }
}
