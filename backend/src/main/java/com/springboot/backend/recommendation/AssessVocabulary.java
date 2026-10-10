package com.springboot.backend.recommendation;

import java.util.List;
import com.springboot.backend.recommendation.classification.TierMapper;

/**
 * Java mirror of the request-side closed vocabularies in {@code ai/app/factors.py}
 * that are not factors ({@link com.springboot.backend.recommendation.classification.Factors}
 * mirrors those). {@code POST /assess} rejects any other value with a 422, so a
 * caller checks against these before sending.
 *
 * <p>Change {@code factors.py} and this file in the same commit;
 * {@code AssessVocabularyTest} fails when they drift.
 */
public final class AssessVocabulary {

    public static final List<String> VERDICTS = List.of(
            TierMapper.NO_MEANINGFUL_CHANGE, TierMapper.WORTH_WATCHING,
            TierMapper.WORTH_CONSIDERING, TierMapper.STRONG_UPGRADE_CANDIDATE);

    public static final List<String> GRADES = List.of("A", "B", "C", "D", "E", "F");

    public static final List<String> STANCES = List.of("POSITIVE", "NEGATIVE", "MIXED");

    public static final List<String> CONDITIONS = List.of("EXCELLENT", "GOOD", "FAIR", "POOR");

    public static final List<String> UPGRADE_URGENCIES = List.of(
            "NOT_URGENT", "SOMEWHAT_URGENT", "URGENT", "CRITICAL");

    public static final List<String> BRAND_FLEXIBILITIES = List.of(
            "EXTREMELY_FLEXIBLE", "FLEXIBLE", "SOMEWHAT_FLEXIBLE", "NOT_FLEXIBLE");

    private AssessVocabulary() {}
}
