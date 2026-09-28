package com.springboot.backend.recommendation.classification;

import org.springframework.stereotype.Component;

/**
 * Maps one upgrade score onto one of the four verdicts (AGENTS.md §7.1).
 *
 * <p>Every threshold is <strong>inclusive of its lower bound</strong>, so a
 * score landing exactly on a boundary takes the higher tier. That choice is
 * arbitrary but it has to be made once and written down, because a boundary
 * handled inconsistently is the classic source of a verdict that cannot be
 * reproduced.
 *
 * <p>The model never sees this mapping and never overrides it (§28.2).
 */
@Component
public class TierMapper {

    public static final String NO_MEANINGFUL_CHANGE = "NO_MEANINGFUL_CHANGE";
    public static final String WORTH_WATCHING = "WORTH_WATCHING";
    public static final String WORTH_CONSIDERING = "WORTH_CONSIDERING";
    public static final String STRONG_UPGRADE_CANDIDATE = "STRONG_UPGRADE_CANDIDATE";

    private final ScoringSettings settings;

    public TierMapper(ScoringSettings settings) {
        this.settings = settings;
    }

    public String toVerdict(double score) {
        if (score >= settings.strongThreshold()) return STRONG_UPGRADE_CANDIDATE;
        if (score >= settings.consideringThreshold()) return WORTH_CONSIDERING;
        if (score >= settings.watchingThreshold()) return WORTH_WATCHING;
        return NO_MEANINGFUL_CHANGE;
    }

    /**
     * Whether a verdict may trigger an email, once it has been persisted.
     *
     * <p>Only the top tier qualifies. This is deliberately narrower than the
     * eventual policy: §27.7 says notification rules must combine verdict,
     * evidence maturity and the user's own settings, and that decision is still
     * open, so nothing here should be read as the finished rule. It is also only
     * <em>eligibility</em> - deciding, not sending. No delivery, deduplication
     * or scheduling exists yet.
     */
    public boolean isEmailEligible(String verdict) {
        return STRONG_UPGRADE_CANDIDATE.equals(verdict);
    }
}
