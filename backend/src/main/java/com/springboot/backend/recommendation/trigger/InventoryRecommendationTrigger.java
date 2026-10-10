package com.springboot.backend.recommendation.trigger;

import com.springboot.backend.service.DeviceInventoryChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
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
 * and a rolled-back save must not produce recommendations. Async, so the
 * inventory request returns without waiting on the pipeline or the AI service.
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

    @Async(RecommendationTriggerConfiguration.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInventoryChanged(DeviceInventoryChanged event) {
        try {
            service.runForDevice(event.userDeviceId(), "DEVICE_" + event.change());
        } catch (RuntimeException e) {
            LOG.error("Inventory-triggered evaluation failed for device {}", event.userDeviceId(), e);
        }
    }
}
