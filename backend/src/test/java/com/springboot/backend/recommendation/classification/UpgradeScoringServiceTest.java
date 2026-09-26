package com.springboot.backend.recommendation.classification;

import static org.junit.jupiter.api.Assertions.*;

import com.springboot.backend.model.Phone;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The scoring rules themselves: normalisation, equal factor weighting, regressions,
 * coverage and determinism.
 */
class UpgradeScoringServiceTest {

    private static final ScoringSettings SETTINGS = ShippedScoringSettings.load();

    private final SpecComparisonService comparison = new SpecComparisonService();
    private final UpgradeScoringService scoring = new UpgradeScoringService(SETTINGS);
    private final TierMapper tiers = new TierMapper(SETTINGS);

    /** A spec sheet covering battery, performance, display and portability. */
    private static Phone phone(long id, int batteryMah, int ramGb, int refreshRateHz, int weightG) {
        return Phone.builder(id)
                .batteryMah(batteryMah)
                .ramGb(ramGb)
                .refreshRateHz(refreshRateHz)
                .weightG(weightG)
                .build();
    }

    private UpgradeScore score(Phone owned, Phone candidate) {
        SpecComparison result =
                comparison.compare(owned, Map.of(), candidate, List.of(), List.of(), null, null, "SGD");
        return scoring.score(result);
    }

    @Test
    void identicalSpecsScoreZeroAndFallInTheLowestTier() {
        Phone owned = phone(1, 4000, 8, 120, 200);
        Phone candidate = phone(2, 4000, 8, 120, 200);

        UpgradeScore result = score(owned, candidate);

        assertTrue(result.sufficientData());
        assertEquals(0.0, result.score());
        assertEquals(TierMapper.NO_MEANINGFUL_CHANGE, tiers.toVerdict(result.score()));
        assertTrue(result.decidingFactors().isEmpty(), "nothing changed, so nothing decided it");
    }

    @Test
    void aSubstantialImprovementAcrossEveryFactorReachesTheTopTier() {
        Phone owned = phone(1, 3000, 4, 60, 240);
        Phone candidate = phone(2, 5000, 12, 144, 180);

        UpgradeScore result = score(owned, candidate);

        assertEquals(TierMapper.STRONG_UPGRADE_CANDIDATE, tiers.toVerdict(result.score()));
    }

    @Test
    void aSmallImprovementDoesNotReachTheTopTier() {
        Phone owned = phone(1, 4000, 8, 120, 200);
        Phone candidate = phone(2, 4200, 8, 120, 198);

        UpgradeScore result = score(owned, candidate);

        assertTrue(result.score() > 0, "a real improvement is not zero");
        assertNotEquals(TierMapper.STRONG_UPGRADE_CANDIDATE, tiers.toVerdict(result.score()));
    }

    @Test
    void aPureRegressionClampsToZeroButIsStillRecordedInTheBreakdown() {
        Phone owned = phone(1, 5000, 12, 144, 180);
        Phone candidate = phone(2, 3000, 4, 60, 240);

        UpgradeScore result = score(owned, candidate);

        assertEquals(0.0, result.score(), "you do not upgrade to a worse phone");
        assertEquals(TierMapper.NO_MEANINGFUL_CHANGE, tiers.toVerdict(result.score()));
        // The clamp must not erase why: each factor still reports it went backwards.
        assertEquals("HIGH_NEGATIVE", result.factorScores().get(Factors.BATTERY).impact());
        assertTrue(result.factorScores().get(Factors.PERFORMANCE).contribution() < 0);
    }

    @Test
    void improvementsAndRegressionsCancelBeforeTheScoreIsClamped() {
        // Battery up hard, portability down hard; the two must offset rather than
        // the improvement alone driving the tier.
        Phone owned = phone(1, 3000, 8, 120, 170);
        Phone candidate = phone(2, 5000, 8, 120, 230);

        UpgradeScore mixed = score(owned, candidate);
        UpgradeScore improvementOnly =
                score(phone(1, 3000, 8, 120, 170), phone(2, 5000, 8, 120, 170));

        assertTrue(
                mixed.score() < improvementOnly.score(),
                "a heavy regression must pull the score down: " + mixed.score() + " vs " + improvementOnly.score());
    }

    @Test
    void everyMeasuredFactorCarriesEqualWeight() {
        // User priorities do not weight the verdict yet (AGENTS.md §27.5), so a
        // full-strength win on one factor is worth the same as on any other.
        Phone owned = phone(1, 4000, 8, 60, 200);
        Phone batteryJump = phone(2, 6000, 8, 60, 200);
        Phone displayJump = phone(2, 4000, 8, 120, 200);

        UpgradeScore battery = score(owned, batteryJump);
        UpgradeScore display = score(owned, displayJump);

        assertEquals(1.0, battery.factorScores().get(Factors.BATTERY).contribution());
        assertEquals(1.0, display.factorScores().get(Factors.DISPLAY).contribution());
        assertEquals(battery.score(), display.score());
    }

    @Test
    void tooFewMeasurableFactorsIsInsufficientDataNotNoChange() {
        // Only battery can be measured, out of every factor a spec could feed.
        Phone owned = Phone.builder(1L).batteryMah(4000).build();
        Phone candidate = Phone.builder(2L).batteryMah(4000).build();

        UpgradeScore result = score(owned, candidate);

        assertFalse(result.sufficientData(), "coverage was " + result.coverage());
        assertTrue(result.coverage() < SETTINGS.minSpecCoverage());
    }

    @Test
    void goodCoverageIsJudgedEvenWhenNothingImproved() {
        // Distinguishes the case above from a genuine "nothing changed".
        Phone owned = phone(1, 4000, 8, 120, 200);
        Phone candidate = phone(2, 4000, 8, 120, 200);

        UpgradeScore result = score(owned, candidate);

        assertTrue(result.sufficientData());
        assertTrue(result.coverage() >= SETTINGS.minSpecCoverage(), "coverage was " + result.coverage());
        assertEquals(0.0, result.score());
    }

    @Test
    void anOutsizedSpecJumpIsCappedSoItCannotSwampEveryOtherFactor() {
        Phone modest = phone(2, 6000, 8, 120, 200);
        Phone absurd = phone(2, 400000, 8, 120, 200);
        Phone owned = phone(1, 4000, 8, 120, 200);

        assertEquals(
                score(owned, modest).score(),
                score(owned, absurd).score(),
                "both are past the improvement cap, so both are a full-strength battery win");
    }

    @Test
    void decidingFactorsAreTheStrongestInfluencesAndComeFromTheClosedVocabulary() {
        // Battery jumps past its cap; RAM and refresh rate improve only partly.
        Phone owned = phone(1, 3000, 8, 60, 200);
        Phone candidate = phone(2, 5000, 10, 90, 200);

        UpgradeScore result = score(owned, candidate);

        assertFalse(result.decidingFactors().isEmpty());
        assertTrue(result.decidingFactors().size() <= 3);
        assertEquals(Factors.BATTERY, result.decidingFactors().get(0));
        result.decidingFactors().forEach(factor ->
                assertTrue(Factors.isFactor(factor), factor + " is not in the closed factor vocabulary"));
    }

    @Test
    void theSameInputsProduceTheSameScoreEveryTime() {
        Phone owned = phone(1, 3210, 6, 90, 213);
        Phone candidate = phone(2, 4870, 11, 133, 187);

        double first = score(owned, candidate).score();
        for (int i = 0; i < 20; i++) {
            assertEquals(first, score(owned, candidate).score());
        }
    }

    @Test
    void theScoreIsRoundedToTheConfiguredPrecision() {
        Phone owned = phone(1, 3210, 6, 90, 213);
        Phone candidate = phone(2, 4870, 11, 133, 187);

        double result = score(owned, candidate).score();

        assertEquals(result, Math.round(result * 100.0) / 100.0, "precision 2 means two decimal places");
    }
}
