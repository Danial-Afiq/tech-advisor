package com.springboot.backend.recommendation;

import com.springboot.backend.recommendation.classification.TierMapper;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Whitelisted dashboard sort orders (ticket 4.3.2).
 *
 * <p>{@code ORDER BY} cannot take bound parameters, so each constant owns a
 * fixed, hand-written clause and user input only <em>selects</em> one - no
 * request text is ever concatenated into SQL. Every clause ends in
 * {@code created_at DESC, id DESC} so ties resolve identically on every request.
 *
 * <p>Clauses reference the aliases {@code r} (recommendations) and
 * {@code latest_price} used by
 * {@link RecommendationRepository#findActiveDashboardRecommendationsByUserId}.
 */
public enum RecommendationSort {

    RELEVANCE("relevance", verdictRankCase("r.verdict") + " DESC, "
            + "(r.factor_analysis -> 'deterministic' ->> 'upgrade_score')::numeric DESC NULLS LAST, "
            + "r.created_at DESC, r.id DESC"),

    PRICE_ASC("price_asc", "latest_price.price ASC NULLS LAST, r.created_at DESC, r.id DESC"),

    PRICE_DESC("price_desc", "latest_price.price DESC NULLS LAST, r.created_at DESC, r.id DESC"),

    RECENCY("recency", "r.created_at DESC, r.id DESC");

    private final String param;
    private final String orderBy;

    RecommendationSort(String param, String orderBy) {
        this.param = param;
        this.orderBy = orderBy;
    }

    public String param() {
        return param;
    }

    /** Fixed SQL fragment; never derived from request text. */
    public String orderBy() {
        return orderBy;
    }

    /**
     * Missing/blank means the default. Matching is case-insensitive; anything
     * else is rejected with a message listing the allowed values.
     */
    public static RecommendationSort parse(String value) {
        if (value == null || value.isBlank()) {
            return RELEVANCE;
        }
        String normalised = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(sort -> sort.param.equals(normalised))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Invalid sort '" + value + "'. Allowed values: "
                                + Arrays.stream(values())
                                        .map(RecommendationSort::param)
                                        .collect(Collectors.joining(", "))));
    }

    /**
     * Tier order, highest first. The single source for the SQL {@code CASE};
     * {@code RecommendationSortTest} fails if a verdict is added to
     * {@link AssessVocabulary#VERDICTS} without a rank here.
     */
    static List<String> verdictsByRank() {
        return Tiers.BY_RANK;
    }

    /** Holder class: enum constants initialise before this enum's own static fields. */
    private static final class Tiers {
        static final List<String> BY_RANK = List.of(
                TierMapper.STRONG_UPGRADE_CANDIDATE,
                TierMapper.WORTH_CONSIDERING,
                TierMapper.WORTH_WATCHING,
                TierMapper.NO_MEANINGFUL_CHANGE);
    }

    private static String verdictRankCase(String column) {
        StringBuilder sql = new StringBuilder("CASE ").append(column);
        int rank = Tiers.BY_RANK.size();
        for (String verdict : Tiers.BY_RANK) {
            sql.append(" WHEN '").append(verdict).append("' THEN ").append(rank--);
        }
        // An unexpected stored value sinks to the bottom instead of failing the query.
        return sql.append(" ELSE 0 END").toString();
    }
}
