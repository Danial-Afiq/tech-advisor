package com.springboot.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RecommendationSortTest {

    @Test
    void everyVerdictHasARankAndNoneIsRankedTwice() {
        assertThat(RecommendationSort.verdictsByRank())
                .containsExactlyInAnyOrderElementsOf(AssessVocabulary.VERDICTS)
                .doesNotHaveDuplicates();
    }

    @Test
    void relevanceRanksTiersHighestFirstNotAlphabetically() {
        String orderBy = RecommendationSort.RELEVANCE.orderBy();

        assertThat(orderBy)
                .contains("WHEN 'STRONG_UPGRADE_CANDIDATE' THEN 4")
                .contains("WHEN 'WORTH_CONSIDERING' THEN 3")
                .contains("WHEN 'WORTH_WATCHING' THEN 2")
                .contains("WHEN 'NO_MEANINGFUL_CHANGE' THEN 1")
                .contains("ELSE 0 END");
    }

    @Test
    void everyOrderEndsWithTheDeterministicTiebreakers() {
        for (RecommendationSort sort : RecommendationSort.values()) {
            assertThat(sort.orderBy()).endsWith("r.created_at DESC, r.id DESC");
        }
    }

    @Test
    void bothPriceOrdersPutMissingPricesLast() {
        assertThat(RecommendationSort.PRICE_ASC.orderBy()).contains("ASC NULLS LAST");
        assertThat(RecommendationSort.PRICE_DESC.orderBy()).contains("DESC NULLS LAST");
    }

    @Test
    void missingOrBlankDefaultsToRelevance() {
        assertThat(RecommendationSort.parse(null)).isEqualTo(RecommendationSort.RELEVANCE);
        assertThat(RecommendationSort.parse("  ")).isEqualTo(RecommendationSort.RELEVANCE);
    }

    @Test
    void parsingIsCaseInsensitive() {
        assertThat(RecommendationSort.parse("price_asc")).isEqualTo(RecommendationSort.PRICE_ASC);
        assertThat(RecommendationSort.parse("PRICE_DESC")).isEqualTo(RecommendationSort.PRICE_DESC);
        assertThat(RecommendationSort.parse("Recency")).isEqualTo(RecommendationSort.RECENCY);
    }

    @Test
    void unknownValuesAreRejectedListingTheAllowedOnes() {
        assertThatThrownBy(() -> RecommendationSort.parse("banana"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relevance, price_asc, price_desc, recency");

        assertThatThrownBy(() -> RecommendationSort.parse("relevance; DROP TABLE users"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
