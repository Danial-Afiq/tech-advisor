package com.springboot.backend.recommendation.classification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.springboot.backend.model.BenchmarkResult;
import com.springboot.backend.model.Phone;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Recommendation verdict scenarios 1, 2, 4 and 6 as pure in-memory unit tests: no database, no AI.
 * The fixture is the base from {@code docs/e2e/recommendation-e2e-expectations.md}; the end-to-end
 * versions live in {@code RecommendationVerdictE2EIntegrationTest}.
 */
class VerdictScenarioScoringTest {

    private static final ScoringSettings SETTINGS = ShippedScoringSettings.load();
    private final SpecComparisonService comparisons = new SpecComparisonService();
    private final UpgradeScoringService scoring = new UpgradeScoringService(SETTINGS);
    private final TierMapper tiers = new TierMapper(SETTINGS);

    /** Old phone and candidate differ only in battery, 3700 to 5500 mAh. */
    private static Phone phone(long id, int batteryMah) {
        return Phone.builder(id).batteryMah(batteryMah).ramGb(8).storageGb(256).refreshRateHz(120)
                .displaySizeInches(new BigDecimal("6.1")).weightG(190).build();
    }

    private static List<BenchmarkResult> geekbench(long productId) {
        return List.of(new BenchmarkResult(productId, "geekbench_multi", BigDecimal.valueOf(3200), "points",
                true, "reco-e2e-test", OffsetDateTime.parse("2026-09-01T00:00:00Z")));
    }

    private String verdict(Map<String, Integer> priorities) {
        SpecComparison comparison = comparisons.compare(PhoneSpecMapping.RULES,
                phone(1, 3700), Map.of(), phone(2, 5500), geekbench(1), geekbench(2),
                new BigDecimal("1000.00"), new BigDecimal("1200.00"), "SGD");
        UpgradeScore score = scoring.score(comparison, priorities);
        assertTrue(score.sufficientData(), "the base fixture must be scorable");
        return tiers.toVerdict(score.score());
    }

    @Test
    void scenario1_bigGainOnTheOnlyPriorityIsAStrongUpgrade() {
        assertEquals(TierMapper.STRONG_UPGRADE_CANDIDATE, verdict(Map.of("battery", 5)));
    }

    @Test
    void scenario2_gainOnlyOnALowPriorityWhileTheTopPriorityIsUnchangedIsNoMeaningfulChange() {
        assertEquals(TierMapper.NO_MEANINGFUL_CHANGE, verdict(Map.of("battery", 1, "performance", 5)));
    }

    /**
     * Locks in the 12 Nov decision: condition and satisfaction never reach scoring. The owned-side
     * scoring input has no such component, so a happy owner of the same phone with the same
     * priorities gets scenario 1's verdict. If someone wires either into scoring, this fails and
     * the decision has to be revisited on purpose.
     */
    @Test
    void scenario4_conditionAndSatisfactionAreNotScoringInputsSoTheVerdictMatchesScenario1() {
        List<String> components = Arrays.stream(UpgradeClassificationService.OwnedSide.class.getRecordComponents())
                .map(RecordComponent::getName).map(n -> n.toLowerCase(Locale.ROOT)).toList();
        assertTrue(components.stream().noneMatch(n -> n.contains("condition") || n.contains("satisf")),
                "scoring input must not carry condition or satisfaction, but has " + components);
        assertEquals(verdict(Map.of("battery", 5)), TierMapper.STRONG_UPGRADE_CANDIDATE);
    }

    @Test
    void scenario6_sameCandidateDifferentPrioritiesGivesDifferentVerdicts() {
        assertEquals(TierMapper.STRONG_UPGRADE_CANDIDATE, verdict(Map.of("battery", 5)));
        assertEquals(TierMapper.WORTH_CONSIDERING, verdict(Map.of("battery", 3, "performance", 1)));
    }
}
