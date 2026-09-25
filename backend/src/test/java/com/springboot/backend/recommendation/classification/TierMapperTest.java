package com.springboot.backend.recommendation.classification;

import static org.junit.jupiter.api.Assertions.*;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Boundary behaviour of the score-to-tier mapping.
 *
 * <p>The verdict is the deterministic half of the output, so unlike the LLM's
 * letter grade (AGENTS.md §13.5) it must be asserted exactly. The thresholds
 * come from {@code application.properties} rather than being restated here, so
 * the boundaries below follow any recalibration automatically.
 */
class TierMapperTest {

    private static final ScoringSettings SETTINGS = ShippedScoringSettings.load();

    /** Just under a threshold, far below the 0.01 step a 2dp score can take. */
    private static final double JUST_BELOW = 0.001;

    private final TierMapper mapper = new TierMapper(SETTINGS);

    static Stream<Arguments> boundaries() {
        double watching = SETTINGS.watchingThreshold();
        double considering = SETTINGS.consideringThreshold();
        double strong = SETTINGS.strongThreshold();
        // Lower bounds are inclusive, so each threshold takes the higher tier.
        // Score is 0-1: 1.0 is a strong upgrade recommendation, 0.0 is not
        // recommended.
        return Stream.of(
                Arguments.of(0.0, TierMapper.NO_MEANINGFUL_CHANGE),
                Arguments.of(watching - JUST_BELOW, TierMapper.NO_MEANINGFUL_CHANGE),
                Arguments.of(watching, TierMapper.WORTH_WATCHING),
                Arguments.of(considering - JUST_BELOW, TierMapper.WORTH_WATCHING),
                Arguments.of(considering, TierMapper.WORTH_CONSIDERING),
                Arguments.of(strong - JUST_BELOW, TierMapper.WORTH_CONSIDERING),
                Arguments.of(strong, TierMapper.STRONG_UPGRADE_CANDIDATE),
                Arguments.of(1.0, TierMapper.STRONG_UPGRADE_CANDIDATE));
    }

    @ParameterizedTest(name = "score {0} -> {1}")
    @MethodSource("boundaries")
    void mapsScoresToTiersAtEveryBoundary(double score, String expected) {
        assertEquals(expected, mapper.toVerdict(score));
    }

    @Test
    void onlyTheTopTierIsEmailEligible() {
        assertTrue(mapper.isEmailEligible(TierMapper.STRONG_UPGRADE_CANDIDATE));
        assertFalse(mapper.isEmailEligible(TierMapper.WORTH_CONSIDERING));
        assertFalse(mapper.isEmailEligible(TierMapper.WORTH_WATCHING));
        assertFalse(mapper.isEmailEligible(TierMapper.NO_MEANINGFUL_CHANGE));
        assertFalse(mapper.isEmailEligible(null));
    }

    @Test
    void misorderedThresholdsFailAtStartupRatherThanProducingWrongTiers() {
        ScoringSettings s = SETTINGS;

        // Nothing downstream would notice bands in the wrong order; it would just
        // quietly hand out plausible-looking wrong verdicts.
        var e = assertThrows(
                IllegalArgumentException.class,
                () -> new ScoringSettings(s.scoringVersion(), s.consideringThreshold(), s.watchingThreshold(),
                        s.strongThreshold(), s.defaultPriority(), s.minSpecCoverage(), s.scorePrecision()));
        assertTrue(e.getMessage().contains("Tier thresholds"), e.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> new ScoringSettings("", s.watchingThreshold(), s.consideringThreshold(),
                        s.strongThreshold(), s.defaultPriority(), s.minSpecCoverage(), s.scorePrecision()));
        assertThrows(IllegalArgumentException.class,
                () -> new ScoringSettings(s.scoringVersion(), s.watchingThreshold(), s.consideringThreshold(),
                        s.strongThreshold(), 9, s.minSpecCoverage(), s.scorePrecision()));
        assertThrows(IllegalArgumentException.class,
                () -> new ScoringSettings(s.scoringVersion(), s.watchingThreshold(), s.consideringThreshold(),
                        s.strongThreshold(), s.defaultPriority(), 1.5, s.scorePrecision()));
        // A threshold above 1.0 is out of range too, since 1.0 is the top of the scale.
        assertThrows(IllegalArgumentException.class,
                () -> new ScoringSettings(s.scoringVersion(), s.watchingThreshold(), s.consideringThreshold(),
                        1.5, s.defaultPriority(), s.minSpecCoverage(), s.scorePrecision()));
    }
}
