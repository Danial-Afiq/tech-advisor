package com.springboot.backend.recommendation.classification;

import com.springboot.backend.exception.ResourceNotFoundException;
import com.springboot.backend.model.BenchmarkResult;
import com.springboot.backend.model.DevicePreference;
import com.springboot.backend.model.Phone;
import com.springboot.backend.model.Product;
import com.springboot.backend.model.UserDevice;
import com.springboot.backend.repository.BenchmarkResultRepository;
import com.springboot.backend.repository.DevicePreferenceRepository;
import com.springboot.backend.repository.PhoneRepository;
import com.springboot.backend.repository.UserDeviceRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Channel A: the deterministic personalised verdict (AGENTS.md §7.1).
 *
 * <p>Answers "does this candidate make sense for this specific user's current
 * device, budget and priorities" using nothing but relational data and
 * arithmetic. No embeddings, no retrieval, no model call - the verdict must be
 * reproducible, and §28.2 is explicit that the LLM never chooses it.
 *
 * <p>Runs after {@code CandidatePruningService} has already reduced the
 * catalogue to compatible, affordable candidates.
 */
@Service
public class UpgradeClassificationService {

    private static final Logger LOG = LoggerFactory.getLogger(UpgradeClassificationService.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    private final UserDeviceRepository userDeviceRepository;
    private final DevicePreferenceRepository devicePreferenceRepository;
    private final PhoneRepository phoneRepository;
    private final BenchmarkResultRepository benchmarkRepository;
    private final SpecComparisonService comparisonService;
    private final UpgradeScoringService scoringService;
    private final TierMapper tierMapper;
    private final ScoringSettings settings;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    public UpgradeClassificationService(
            UserDeviceRepository userDeviceRepository,
            DevicePreferenceRepository devicePreferenceRepository,
            PhoneRepository phoneRepository,
            BenchmarkResultRepository benchmarkRepository,
            SpecComparisonService comparisonService,
            UpgradeScoringService scoringService,
            TierMapper tierMapper,
            ScoringSettings settings) {

        this.userDeviceRepository = userDeviceRepository;
        this.devicePreferenceRepository = devicePreferenceRepository;
        this.phoneRepository = phoneRepository;
        this.benchmarkRepository = benchmarkRepository;
        this.comparisonService = comparisonService;
        this.scoringService = scoringService;
        this.tierMapper = tierMapper;
        this.settings = settings;
    }

    /**
     * Classifies one candidate against one owned device.
     *
     * @param userDeviceId      the owned device, which supplies the reference
     *                          product and the preferences
     * @param candidateProductId the product being evaluated
     * @param candidatePrice    the latest observed price, normally carried
     *                          straight through from
     *                          {@code CandidateProduct.getLatestPrice()} rather
     *                          than re-read
     */
    @Transactional(readOnly = true)
    public UpgradeClassification classify(
            Long userDeviceId, Long candidateProductId, BigDecimal candidatePrice) {

        UserDevice device = userDeviceRepository.findByIdAndIsCurrentTrue(userDeviceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Owned device " + userDeviceId + " not found"));

        DevicePreference preference = devicePreferenceRepository.findById(userDeviceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Upgrade preferences not configured for device " + userDeviceId));

        Product owned = device.getProduct();
        if (owned == null) {
            throw new ResourceNotFoundException(
                    "Owned device " + userDeviceId + " has no catalogue link, so it cannot be compared");
        }

        Phone ownedSpecs = phoneRepository.findById(owned.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No specifications recorded for owned product " + owned.getId()));

        Phone candidateSpecs = phoneRepository.findById(candidateProductId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No specifications recorded for candidate product " + candidateProductId));

        List<BenchmarkResult> ownedBenchmarks = benchmarkRepository.findLatestPerBenchmark(owned.getId());
        List<BenchmarkResult> candidateBenchmarks = benchmarkRepository.findLatestPerBenchmark(candidateProductId);

        SpecComparison comparison = comparisonService.compare(
                ownedSpecs,
                readJsonObject(device.getSpecOverrides(), "spec_overrides", userDeviceId),
                candidateSpecs,
                ownedBenchmarks,
                candidateBenchmarks,
                candidatePrice,
                preference.getBudget(),
                preference.getCurrency());

        Map<String, Integer> priorities = readPriorities(preference, userDeviceId);
        UpgradeScore score = scoringService.score(comparison, priorities);

        // The early preference-gate exit (§7.1): when the comparison rests on too
        // little data, say NO_MEANINGFUL_CHANGE rather than inventing a tier. The
        // caller then skips retrieval and the model call entirely, which is the
        // whole point of the gate - an unjudgeable candidate must not cost money.
        String verdict = score.sufficientData()
                ? tierMapper.toVerdict(score.score())
                : TierMapper.NO_MEANINGFUL_CHANGE;

        if (!score.sufficientData()) {
            LOG.info(
                    "Device {} vs candidate {}: only {}% of the user's factors were measurable, "
                            + "below the {}% minimum; returning {} without scoring",
                    userDeviceId,
                    candidateProductId,
                    Math.round(score.coverage() * 100),
                    Math.round(settings.minSpecCoverage() * 100),
                    TierMapper.NO_MEANINGFUL_CHANGE);
        }

        return new UpgradeClassification(
                verdict, score.score(), score, comparison, settings.scoringVersion());
    }

    /**
     * Reads {@code device_preferences.priorities} into factor weights.
     *
     * <p>Keys outside the closed factor vocabulary are dropped rather than
     * weighted, since a priority the grader has no name for cannot be reported
     * on and would silently skew the denominator (§11.4).
     */
    private Map<String, Integer> readPriorities(DevicePreference preference, Long userDeviceId) {
        Map<String, Object> raw = readJsonObject(preference.getPriorities(), "priorities", userDeviceId);
        return raw.entrySet().stream()
                .filter(entry -> Factors.isFactor(entry.getKey()))
                .filter(entry -> entry.getValue() instanceof Number)
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, entry -> ((Number) entry.getValue()).intValue()));
    }

    /**
     * Parses one of the user-controlled JSONB columns.
     *
     * <p>Malformed JSON degrades to empty rather than throwing: these columns
     * are free-form and user-owned, and a bad {@code spec_overrides} blob should
     * cost the overrides, not the whole assessment. It is logged because silently
     * ignoring a user's stated configuration is not something to do quietly.
     */
    private Map<String, Object> readJsonObject(String raw, String column, Long userDeviceId) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = json.readValue(raw, JSON_OBJECT);
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException e) {
            LOG.warn("Ignoring unreadable {} on device {}: {}", column, userDeviceId, e.getMessage());
            return Map.of();
        }
    }
}
