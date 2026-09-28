package com.springboot.backend.recommendation;

import com.springboot.backend.exception.ResourceNotFoundException;
import com.springboot.backend.recommendation.classification.UpgradeClassification;
import com.springboot.backend.recommendation.classification.UpgradeClassificationService;
import com.springboot.backend.recommendation.classification.UpgradeClassificationService.OwnedSide;
import com.springboot.backend.repository.CandidateProduct;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The deterministic half of one owned device's evaluation, end to end:
 * shortlist the catalogue, then classify every survivor (AGENTS.md §7.1).
 *
 * <p>Stops before Channel B. Nothing here retrieves reviews, calls the AI
 * service or persists a recommendation - the result says which candidates
 * are worth that spend, and whatever drives this next decides what to do
 * with them. There is also no trigger: no {@code @Scheduled}, no controller.
 */
@Service
public class CandidateEvaluationService {

    private static final Logger LOG = LoggerFactory.getLogger(CandidateEvaluationService.class);

    /**
     * Highest score first. Ties break on product id so the same catalogue
     * always produces the same order, which matters once the top of this list
     * decides what gets an LLM call.
     */
    private static final Comparator<CandidateEvaluation.Classified> RANKING =
            Comparator.comparingDouble(
                            (CandidateEvaluation.Classified c) -> c.classification().upgradeScore())
                    .reversed()
                    .thenComparing(c -> c.candidate().getProductId());

    private final CandidatePruningService pruningService;
    private final UpgradeClassificationService classificationService;

    public CandidateEvaluationService(
            CandidatePruningService pruningService,
            UpgradeClassificationService classificationService) {

        this.pruningService = pruningService;
        this.classificationService = classificationService;
    }

    /**
     * Evaluates every viable candidate for one owned device.
     *
     * <p>Fails outright when the owned device itself cannot be evaluated (no
     * device, no preferences, no catalogue link, no spec sheet), because then no
     * candidate can be - even when the shortlist is empty, so an unevaluable
     * device never passes for one with nothing to recommend. A candidate with no spec sheet only costs that
     * candidate: it is recorded as skipped and the run carries on.
     *
     * <p>One read-only transaction covers the whole run, so the shortlist and
     * the scores are read from the same snapshot of the catalogue.
     */
    @Transactional(readOnly = true)
    public CandidateEvaluation evaluate(Long userDeviceId) {
        List<CandidateProduct> candidates = pruningService.getViableCandidates(userDeviceId);
        OwnedSide owned = classificationService.loadOwnedSide(userDeviceId);

        List<CandidateEvaluation.Classified> classified = new ArrayList<>();
        List<CandidateEvaluation.Skipped> skipped = new ArrayList<>();

        for (CandidateProduct candidate : candidates) {
            try {
                UpgradeClassification classification = classificationService.classify(
                        owned, candidate.getProductId(), candidate.getLatestPrice());
                classified.add(new CandidateEvaluation.Classified(candidate, classification));
            } catch (ResourceNotFoundException e) {
                LOG.warn("Device {}: skipping candidate {}: {}",
                        userDeviceId, candidate.getProductId(), e.getMessage());
                skipped.add(new CandidateEvaluation.Skipped(candidate, e.getMessage()));
            }
        }

        classified.sort(RANKING);
        return new CandidateEvaluation(userDeviceId, owned.userId(), classified, skipped);
    }
}
