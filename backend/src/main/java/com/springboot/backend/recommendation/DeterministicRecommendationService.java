package com.springboot.backend.recommendation;

import com.springboot.backend.recommendation.classification.TierMapper;
import com.springboot.backend.recommendation.classification.UpgradeClassification;
import com.springboot.backend.repository.CandidateProduct;
import com.springboot.backend.repository.UserDeviceRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the deterministic evaluation for one owned device and persists every
 * classified candidate into {@code recommendations} (AGENTS.md §18.11).
 *
 * <p>Rows written here carry Channel A only. The evidence grade
 * ({@code confidence}) is left {@code NULL} on every one of them, whatever the
 * verdict: no model produced a grade, and a deterministic row never claims
 * one (§14.12). Rows past the preference gate are later superseded by the AI
 * step through {@link RecommendationService#assessAndPersist}.
 * {@code ai_model} and {@code prompt_version} stay null for the same reason,
 * and {@code reasoning} is a Java template rather than model prose.
 *
 * <p>No trigger: nothing schedules or exposes this yet. {@link #evaluateAllDevices()}
 * is the entry point the trigger is meant to call.
 */
@Service
public class DeterministicRecommendationService {

    private static final Map<String, String> VERDICT_LABELS = Map.of(
            TierMapper.NO_MEANINGFUL_CHANGE, "No meaningful change",
            TierMapper.WORTH_WATCHING, "Worth watching",
            TierMapper.WORTH_CONSIDERING, "Worth considering",
            TierMapper.STRONG_UPGRADE_CANDIDATE, "Strong upgrade candidate");

    private static final Logger LOG = LoggerFactory.getLogger(DeterministicRecommendationService.class);

    private final CandidateEvaluationService evaluationService;
    private final RecommendationRepository repository;
    private final UserDeviceRepository userDeviceRepository;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    public DeterministicRecommendationService(
            CandidateEvaluationService evaluationService,
            RecommendationRepository repository,
            UserDeviceRepository userDeviceRepository) {

        this.evaluationService = evaluationService;
        this.repository = repository;
        this.userDeviceRepository = userDeviceRepository;
    }

    /**
     * One full deterministic run: every evaluable device (current, linked to a
     * catalogue product, with preferences), each through
     * {@link #evaluateAndPersist}. This is what the trigger calls.
     *
     * <p>Deliberately not {@code @Transactional}: each device commits on its
     * own, so one device's failure neither rolls back nor blocks the others.
     * Any exception is caught per device, logged and reported in the result -
     * a batch that dies on the first bad device would leave every later user
     * with stale recommendations. Nothing is written for a failed device, so its
     * previous rows stand as they were.
     */
    public BatchRun evaluateAllDevices() {
        List<Long> deviceIds = userDeviceRepository.findEvaluableDeviceIds();

        List<PersistedEvaluation> completed = new ArrayList<>();
        Map<Long, String> failed = new LinkedHashMap<>();

        for (Long deviceId : deviceIds) {
            try {
                completed.add(evaluateAndPersist(deviceId));
            } catch (RuntimeException e) {
                LOG.error("Deterministic evaluation failed for device {}", deviceId, e);
                failed.put(deviceId, e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        LOG.info("Deterministic run finished: {} devices evaluated, {} failed",
                completed.size(), failed.size());
        return new BatchRun(completed, failed);
    }

    /**
     * Evaluates one owned device and replaces its persisted results.
     *
     * <p>Candidates that could not be classified are not written: a verdict
     * the classifier did not produce must not be fabricated (§12). They are
     * still counted as shortlisted, so any row they had from an earlier run is
     * left alone rather than deleted.
     */
    public PersistedEvaluation evaluateAndPersist(Long userDeviceId) {
        CandidateEvaluation evaluation = evaluationService.evaluate(userDeviceId);

        Set<Long> shortlisted = new LinkedHashSet<>();
        evaluation.ranked().forEach(c -> shortlisted.add(c.candidate().getProductId()));
        evaluation.skipped().forEach(s -> shortlisted.add(s.candidate().getProductId()));

        List<RecommendationRecord> records = new ArrayList<>();
        for (CandidateEvaluation.Classified classified : evaluation.ranked()) {
            records.add(toRecord(evaluation, classified));
        }

        int deleted = repository.replaceForDevice(userDeviceId, shortlisted, records);
        return new PersistedEvaluation(evaluation, records.size(), deleted);
    }

    private RecommendationRecord toRecord(CandidateEvaluation evaluation, CandidateEvaluation.Classified classified) {
        CandidateProduct candidate = classified.candidate();
        UpgradeClassification classification = classified.classification();
        return new RecommendationRecord(
                evaluation.userId(),
                evaluation.userDeviceId(),
                candidate.getProductId(),
                null,
                classification.verdict(),
                null,
                inputSnapshot(candidate, classification),
                factorAnalysis(classification),
                templateReasoning(classification),
                null,
                null);
    }

    /**
     * The same top-level keys {@link RecommendationService} writes, minus the
     * ones only an AI round-trip produces (retrieval parameters, chunk ids), so
     * one reader can handle both kinds of row.
     */
    private Map<String, Object> inputSnapshot(CandidateProduct candidate, UpgradeClassification classification) {
        Map<String, Object> candidateInfo = new LinkedHashMap<>();
        candidateInfo.put("product_id", candidate.getProductId());
        candidateInfo.put("brand", candidate.getBrand());
        candidateInfo.put("model_name", candidate.getModelName());
        candidateInfo.put("latest_price", candidate.getLatestPrice());
        candidateInfo.put("currency", candidate.getCurrency());

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("candidate", candidateInfo);
        snapshot.put("computed", toMap(classification.toComputed(null)));
        snapshot.put("analysis", toMap(classification.toAnalysis()));
        return snapshot;
    }

    /** §14.12 shape, with the evidence half present but empty until Channel B runs. */
    private Map<String, Object> factorAnalysis(UpgradeClassification classification) {
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("deterministic", classification.toDeterministicFactors());
        analysis.put("evidence", List.of());
        analysis.put("irrelevant_chunk_ids", List.of());
        return analysis;
    }

    /**
     * The fixed-wording explanation §12 calls for when no model writes one.
     * Built only from what the classifier decided, so it cannot say anything
     * the numbers do not.
     */
    static String templateReasoning(UpgradeClassification classification) {
        if (classification.isInsufficientSpecCoverage()) {
            return "Not enough comparable specifications to judge this upgrade against your current device.";
        }

        String reasoning = String.format(
                Locale.ROOT,
                "%s based on specifications and price (upgrade score %.2f).",
                VERDICT_LABELS.getOrDefault(classification.verdict(), classification.verdict()),
                classification.upgradeScore());

        List<String> deciding = classification.decidingFactors();
        if (!deciding.isEmpty()) {
            reasoning += " Biggest differences: " + String.join(", ", deciding).replace('_', ' ') + ".";
        }
        return reasoning;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object value) {
        return json.convertValue(value, Map.class);
    }

    /**
     * @param evaluation the evaluation that was persisted
     * @param saved      rows inserted, one per classified candidate
     * @param deleted    rows removed for candidates no longer shortlisted
     */
    public record PersistedEvaluation(CandidateEvaluation evaluation, int saved, int deleted) {}

    /**
     * @param completed one entry per device that was evaluated and persisted,
     *                  in device-id order
     * @param failed    device id to the reason it could not be evaluated
     */
    public record BatchRun(List<PersistedEvaluation> completed, Map<Long, String> failed) {

        public BatchRun {
            completed = List.copyOf(completed);
            failed = Collections.unmodifiableMap(new LinkedHashMap<>(failed));
        }
    }
}
