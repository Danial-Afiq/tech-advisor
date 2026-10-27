package com.springboot.backend.recommendation.classification;

import static org.junit.jupiter.api.Assertions.*;

import com.springboot.backend.model.Phone;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class PriorityScoringTest {
    private static final ScoringSettings SETTINGS = ShippedScoringSettings.load();
    private final SpecComparisonService comparisons = new SpecComparisonService();
    private final UpgradeScoringService scoring = new UpgradeScoringService(SETTINGS);
    private final TierMapper tiers = new TierMapper(SETTINGS);

    record Specs(int battery, int ram, int refresh, int weight) {
        Phone phone(long id) {
            return Phone.builder(id).batteryMah(battery).ramGb(ram)
                    .refreshRateHz(refresh).weightG(weight).build();
        }
    }

    record Fixture(String name, Specs owned, Specs candidate, Map<String, Integer> priorities,
                   BigDecimal budget, BigDecimal price, double expectedScore, String expectedVerdict) {
        @Override public String toString() { return name; }
    }

    static List<Fixture> fixtures() throws IOException {
        try (var input = PriorityScoringTest.class.getResourceAsStream("/scoring/priority-fixtures.json")) {
            assertNotNull(input);
            return JsonMapper.builder().build().readValue(input, new TypeReference<List<Fixture>>() {});
        }
    }

    SpecComparison comparison(Fixture fixture) {
        return comparisons.compare(PhoneSpecMapping.RULES,
                fixture.owned().phone(1), Map.of(), fixture.candidate().phone(2),
                List.of(), List.of(), fixture.price(), fixture.budget(), "SGD");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void fixedPriorityFixturesHaveExactScoresAndVerdicts(Fixture fixture) {
        UpgradeScore score = scoring.score(comparison(fixture), fixture.priorities());
        assertTrue(score.sufficientData());
        assertEquals(fixture.expectedScore(), score.score());
        assertEquals(fixture.expectedVerdict(), tiers.toVerdict(score.score()));
    }

    @Test
    void topPriorityGainBeatsLowPrioritySpecsAndOppositeUsersGetDifferentVerdicts() throws IOException {
        List<Fixture> fixtures = fixtures();
        UpgradeScore low = scoring.score(comparison(fixtures.get(0)), fixtures.get(0).priorities());
        UpgradeScore high = scoring.score(comparison(fixtures.get(1)), fixtures.get(1).priorities());
        UpgradeScore opposite = scoring.score(comparison(fixtures.get(2)), fixtures.get(2).priorities());

        assertTrue(low.score() < SETTINGS.consideringThreshold());
        assertTrue(high.score() >= SETTINGS.consideringThreshold());
        assertTrue(high.score() > low.score());
        assertEquals(fixtures.get(1).candidate(), fixtures.get(2).candidate());
        assertNotEquals(high.score(), opposite.score());
        assertNotEquals(tiers.toVerdict(high.score()), tiers.toVerdict(opposite.score()));
    }

    @Test
    void weightsAndSignedContributionsExplainTheAggregate() throws IOException {
        Fixture fixture = fixtures().get(1);
        UpgradeScore score = scoring.score(comparison(fixture), fixture.priorities());
        var battery = score.factorScores().get(Factors.BATTERY);
        var performance = score.factorScores().get(Factors.PERFORMANCE);
        assertEquals(5, battery.priority());
        assertEquals(5.0 / 6, battery.weight(), 1e-12);
        assertEquals(battery.weight() / 5, performance.weight(), 1e-12);
        assertEquals(0.8, battery.improvement(), 1e-12);
        assertEquals(battery.improvement() * battery.weight(), battery.contribution(), 1e-12);
        assertEquals(1.0, score.factorScores().values().stream().mapToDouble(UpgradeScore.FactorScore::weight).sum(), 1e-12);
        assertEquals(List.of(Factors.BATTERY), score.decidingFactors());
        double sum = score.factorScores().values().stream().mapToDouble(UpgradeScore.FactorScore::contribution).sum();
        assertEquals(score.score(), BigDecimal.valueOf(sum).setScale(SETTINGS.scorePrecision(),
                java.math.RoundingMode.HALF_UP).doubleValue());
    }

    static Stream<Map<String, ?>> missingProfiles() {
        return Stream.of(null, Map.of(), Map.of("battery", 0, "performance", 0),
                Map.of("vibes", 5), Map.of("camera", 5), Map.of("battery", "bad"));
    }

    @ParameterizedTest
    @MethodSource("missingProfiles")
    void missingZeroUnknownOrUnmeasurableProfilesFallBackToEqualWeights(Map<String, ?> priorities) throws IOException {
        SpecComparison comparison = comparison(fixtures().get(1));
        UpgradeScore score = scoring.score(comparison, priorities);
        assertEquals(scoring.score(comparison), score);
        for (var factor : score.factorScores().values()) {
            assertEquals(SETTINGS.defaultPriority(), factor.priority());
            assertEquals(1.0 / score.factorScores().size(), factor.weight(), 1e-12);
        }
    }

    @Test
    void zeroPriorityExcludesAFactorAndMissingFactorsUseConfiguredDefault() throws IOException {
        UpgradeScore score = scoring.score(comparison(fixtures().get(1)), Map.of("battery", 0, "performance", 1));
        assertEquals(0.0, score.score());
        assertEquals(0.0, score.factorScores().get(Factors.BATTERY).weight());
        assertEquals(SETTINGS.missingPriority(), score.factorScores().get(Factors.DISPLAY).priority());
        assertEquals(1.0, score.factorScores().get(Factors.PERFORMANCE).weight());
    }

    @Test
    void negativeHugeAndOutOfRangePrioritiesClampWithoutOverflow() throws IOException {
        UpgradeScore score = scoring.score(comparison(fixtures().get(1)),
                Map.of("battery", new BigInteger("9999999999999999999999999"),
                        "performance", -12, "display", SETTINGS.priorityMax() + 1));
        assertEquals(SETTINGS.priorityMax(), score.factorScores().get(Factors.BATTERY).priority());
        assertEquals(SETTINGS.priorityMin(), score.factorScores().get(Factors.PERFORMANCE).priority());
        assertEquals(SETTINGS.priorityMax(), score.factorScores().get(Factors.DISPLAY).priority());
    }

    @Test
    void invalidValuesUseMissingDefaultAndUnknownKeysDoNotDiluteWeights() throws IOException {
        Map<String, Object> priorities = new LinkedHashMap<>();
        priorities.put("battery", 5);
        priorities.put("performance", 1.5);
        priorities.put("display", null);
        priorities.put("portability", Double.NaN);
        priorities.put("value", "5");
        priorities.put("unknown", 1000);
        UpgradeScore score = scoring.score(comparison(fixtures().get(1)), priorities);
        assertEquals(1.0, score.factorScores().get(Factors.BATTERY).weight());
        assertFalse(score.factorScores().containsKey("unknown"));
        assertTrue(Double.isFinite(score.score()));
    }

    @Test
    void priorityMapOrderAndRepeatedCallsCannotChangeScoreOrVerdict() throws IOException {
        SpecComparison comparison = comparison(fixtures().get(1));
        Map<String, Integer> first = new LinkedHashMap<>();
        first.put("battery", 5);
        first.put("performance", 1);
        Map<String, Integer> reversed = new LinkedHashMap<>();
        reversed.put("performance", 1);
        reversed.put("battery", 5);
        UpgradeScore expected = scoring.score(comparison, first);
        for (int i = 0; i < 20; i++) {
            UpgradeScore result = scoring.score(comparison, reversed);
            assertEquals(expected, result);
            assertEquals(tiers.toVerdict(expected.score()), tiers.toVerdict(result.score()));
        }
    }

    @Test
    void budgetHeadroomStillContributesThroughValue() throws IOException {
        Fixture fixture = fixtures().get(1);
        Phone owned = fixture.owned().phone(1);
        Phone candidate = fixture.candidate().phone(2);
        var cheap = comparisons.compare(PhoneSpecMapping.RULES,
                owned, Map.of(), candidate, List.of(), List.of(),
                new BigDecimal("700"), fixture.budget(), "SGD");
        var expensive = comparisons.compare(PhoneSpecMapping.RULES,
                owned, Map.of(), candidate, List.of(), List.of(),
                new BigDecimal("1100"), fixture.budget(), "SGD");
        var priorities = Map.of("battery", 5, "value", 1);
        assertTrue(scoring.score(cheap, priorities).score() > scoring.score(expensive, priorities).score());
        assertTrue(scoring.score(expensive, priorities).factorScores().get(Factors.VALUE).contribution() < 0);
    }

    @Test
    void emptyComparisonRemainsAnInsufficientDataResult() {
        UpgradeScore score = scoring.score(
                new SpecComparison(List.of(), Map.of(), null, null, List.of(), List.of()),
                Map.of("battery", 5));
        assertFalse(score.sufficientData());
        assertEquals(0.0, score.score());
        assertTrue(score.factorScores().isEmpty());
    }

    @Test
    void configuredScaleAndDefaultsControlResolvedPriorities() throws IOException {
        ScoringSettings s = SETTINGS;
        var configured = new ScoringSettings(s.scoringVersion(), s.watchingThreshold(), s.consideringThreshold(),
                s.strongThreshold(), s.minSpecCoverage(), s.scorePrecision(), 0, 10, 2, 1);
        var scorer = new UpgradeScoringService(configured);
        SpecComparison comparison = comparison(fixtures().get(1));
        UpgradeScore selected = scorer.score(comparison, Map.of("battery", 9));
        assertEquals(9, selected.factorScores().get(Factors.BATTERY).priority());
        assertEquals(1, selected.factorScores().get(Factors.PERFORMANCE).priority());
        assertEquals(9.0 / 13, selected.factorScores().get(Factors.BATTERY).weight(), 1e-12);
        assertTrue(scorer.score(comparison).factorScores().values().stream().allMatch(f -> f.priority() == 2));
    }

    @Test
    void invalidPriorityConfigurationFailsBeforeScoring() {
        ScoringSettings s = SETTINGS;
        assertThrows(IllegalArgumentException.class, () -> new ScoringSettings(s.scoringVersion(),
                s.watchingThreshold(), s.consideringThreshold(), s.strongThreshold(), s.minSpecCoverage(),
                s.scorePrecision(), -1, 5, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ScoringSettings(s.scoringVersion(),
                s.watchingThreshold(), s.consideringThreshold(), s.strongThreshold(), s.minSpecCoverage(),
                s.scorePrecision(), 0, 5, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ScoringSettings(s.scoringVersion(),
                s.watchingThreshold(), s.consideringThreshold(), s.strongThreshold(), s.minSpecCoverage(),
                s.scorePrecision(), 0, 5, 1, 6));
    }
}
