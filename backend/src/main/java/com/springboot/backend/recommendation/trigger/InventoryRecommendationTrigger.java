package com.springboot.backend.recommendation.trigger;

import com.springboot.backend.service.DeviceInventoryChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Trigger 2: re-runs the recommendation pipeline for one device when the user
 * adds it to their inventory or edits it, so they see verdicts without waiting
 * for a batch run. Only that device's recommendations change.
 *
 * <p>After commit, because the run reads the device and its preferences back
 * from the database on another thread: before commit they are not visible there,
 * and a rolled-back save must not produce recommendations.
 *
 * <p>The listener itself is synchronous and only queues the run
 * ({@link RecommendationTriggerService#queueDeviceRun}), so the inventory request
 * still returns without waiting on the pipeline or the AI service. Queuing here,
 * rather than via {@code @Async}, is what stamps the run with the device's
 * generation in save order, so a newer save supersedes an older run still in
 * the queue.
 *
 * <p>Any failure is logged and swallowed: the inventory change has already
 * committed and must not look failed because its recommendations did.
 */
@Component
@ConditionalOnProperty(name = "recommendation.inventory-trigger-enabled", havingValue = "true", matchIfMissing = true)
public class InventoryRecommendationTrigger {

    private static final Logger LOG = LoggerFactory.getLogger(InventoryRecommendationTrigger.class);

    private final RecommendationTriggerService service;

    public InventoryRecommendationTrigger(RecommendationTriggerService service) {
        this.service = service;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInventoryChanged(DeviceInventoryChanged event) {
        try {
            String cause = switch (event.change()) {
                case ADDED -> RecommendationTriggerService.CAUSE_DEVICE_ADDED;
                case UPDATED -> RecommendationTriggerService.CAUSE_DEVICE_UPDATED;
            };
            service.queueDeviceRun(event.userDeviceId(), cause);
        } catch (RuntimeException e) {
            LOG.error("Could not queue the inventory-triggered evaluation for device {}", event.userDeviceId(), e);
        }
    }
}
