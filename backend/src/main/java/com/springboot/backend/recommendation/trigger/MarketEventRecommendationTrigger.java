package com.springboot.backend.recommendation.trigger;

import com.springboot.backend.marketevent.MarketEventRecorded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Trigger 1: re-evaluates every affected owned device when a market event is
 * recorded, whichever path recorded it (admin entry or catalogue ingestion all go
 * through {@code MarketEventService}).
 *
 * <p>After commit and async for the same reasons as
 * {@link InventoryRecommendationTrigger}: the run must see the committed event,
 * and the ingestion run or admin request must not wait for it.
 */
@Component
@ConditionalOnProperty(name = "recommendation.market-event-trigger-enabled", havingValue = "true", matchIfMissing = true)
public class MarketEventRecommendationTrigger {

    private static final Logger LOG = LoggerFactory.getLogger(MarketEventRecommendationTrigger.class);

    private final RecommendationTriggerService service;

    public MarketEventRecommendationTrigger(RecommendationTriggerService service) {
        this.service = service;
    }

    @Async(RecommendationTriggerConfiguration.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMarketEventRecorded(MarketEventRecorded event) {
        try {
            service.runForMarketEvent(event.marketEventId(), RecommendationTriggerService.CAUSE_MARKET_EVENT);
        } catch (RuntimeException e) {
            LOG.error("Market-event-triggered evaluation failed for event {}", event.marketEventId(), e);
        }
    }
}
