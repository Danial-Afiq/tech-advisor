package com.springboot.backend.recommendation;

import com.springboot.backend.repository.UserDeviceRepository;
import com.springboot.backend.service.DeviceInventoryChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Re-runs the deterministic pipeline for one device when the user adds it to
 * their inventory or edits it, so they see verdicts without waiting for a
 * batch run.
 *
 * <p>After commit, because the evaluation reads the device and its preferences
 * back from the database on another thread: before commit they are not
 * visible there, and a rolled-back save must not produce recommendations.
 * Async, so the inventory request returns without waiting on the pipeline.
 *
 * <p>A device that cannot be evaluated yet (no catalogue link, no preferences)
 * is skipped quietly - that is a normal state for a freshly added device, not
 * an error. Any failure is logged and swallowed: the inventory change has
 * already committed and must not look failed because its recommendations did.
 *
 * <p>Deterministic only. It does not hand {@code worthAssessing()} to the AI
 * step; that stays with the trigger ticket (§18.11).
 */
@Component
@ConditionalOnProperty(name = "recommendation.inventory-trigger-enabled", havingValue = "true", matchIfMissing = true)
public class InventoryRecommendationTrigger {

    private static final Logger LOG = LoggerFactory.getLogger(InventoryRecommendationTrigger.class);

    private final DeterministicRecommendationService service;
    private final UserDeviceRepository userDeviceRepository;

    public InventoryRecommendationTrigger(
            DeterministicRecommendationService service, UserDeviceRepository userDeviceRepository) {

        this.service = service;
        this.userDeviceRepository = userDeviceRepository;
    }

    @Async(RecommendationTriggerConfiguration.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInventoryChanged(DeviceInventoryChanged event) {
        Long deviceId = event.userDeviceId();
        try {
            if (!userDeviceRepository.isEvaluable(deviceId)) {
                LOG.info("Device {} changed but is not evaluable yet (needs a catalogue link and a budget)", deviceId);
                return;
            }

            DeterministicRecommendationService.PersistedEvaluation result = service.evaluateAndPersist(deviceId);
            LOG.info("Device {} evaluated after inventory change: {} saved, {} deleted",
                    deviceId, result.saved(), result.deleted());
        } catch (RuntimeException e) {
            LOG.error("Inventory-triggered evaluation failed for device {}", deviceId, e);
        }
    }
}
