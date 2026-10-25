package com.springboot.backend.email;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecommendationEmailSettingsTest {

    @Test
    void strongUpgradeWithGradeAOrBIsEligible() {
        RecommendationEmailSettings settings = settings("STRONG_UPGRADE_CANDIDATE", "B", 3);

        assertThat(settings.isEligible("STRONG_UPGRADE_CANDIDATE", "A")).isTrue();
        assertThat(settings.isEligible("STRONG_UPGRADE_CANDIDATE", "B")).isTrue();
    }

    @Test
    void gradesBelowTheMinimumAreNotEligible() {
        RecommendationEmailSettings settings = settings("STRONG_UPGRADE_CANDIDATE", "B", 3);

        assertThat(settings.isEligible("STRONG_UPGRADE_CANDIDATE", "C")).isFalse();
        assertThat(settings.isEligible("STRONG_UPGRADE_CANDIDATE", "D")).isFalse();
        assertThat(settings.isEligible("STRONG_UPGRADE_CANDIDATE", "E")).isFalse();
        assertThat(settings.isEligible("STRONG_UPGRADE_CANDIDATE", "F")).isFalse();
        assertThat(settings.isEligible("STRONG_UPGRADE_CANDIDATE", "-")).isFalse();
        assertThat(settings.isEligible("STRONG_UPGRADE_CANDIDATE", null)).isFalse();
    }

    @Test
    void lowerVerdictsAreNotEligible() {
        RecommendationEmailSettings settings = settings("STRONG_UPGRADE_CANDIDATE", "B", 3);

        assertThat(settings.isEligible("WORTH_CONSIDERING", "A")).isFalse();
        assertThat(settings.isEligible("WORTH_WATCHING", "A")).isFalse();
        assertThat(settings.isEligible("NO_MEANINGFUL_CHANGE", "A")).isFalse();
    }

    @Test
    void eligibilityUsesTheConfiguredVerdictAndGrade() {
        RecommendationEmailSettings settings = settings("WORTH_CONSIDERING", "C", 3);

        assertThat(settings.isEligible("WORTH_CONSIDERING", "A")).isTrue();
        assertThat(settings.isEligible("WORTH_CONSIDERING", "C")).isTrue();
        assertThat(settings.isEligible("WORTH_CONSIDERING", "D")).isFalse();
        assertThat(settings.isEligible("STRONG_UPGRADE_CANDIDATE", "A")).isFalse();
    }

    @Test
    void invalidMinimumGradeIsRejected() {
        assertThatThrownBy(() -> settings("STRONG_UPGRADE_CANDIDATE", "-", 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimum-grade");
    }

    @Test
    void fewerThanOneAttemptIsRejected() {
        assertThatThrownBy(() -> settings("STRONG_UPGRADE_CANDIDATE", "B", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum-attempts");
    }

    private RecommendationEmailSettings settings(String verdict, String grade, int attempts) {
        return new RecommendationEmailSettings(
                verdict,
                grade,
                attempts,
                "http://localhost:5173/dashboard",
                "no-reply@techadvisor.local"
        );
    }
}