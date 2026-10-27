package com.springboot.backend.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RecommendationEmailSettings {

    private static final List<String> GRADES = List.of("A", "B", "C", "D", "E", "F");

    private final String eligibleVerdict;
    private final String minimumGrade;
    private final int maximumAttempts;
    private final String frontendRecommendationUrl;
    private final String fromAddress;

    public RecommendationEmailSettings(
            @Value("${recommendation.email.eligible-verdict:STRONG_UPGRADE_CANDIDATE}") String eligibleVerdict,
            @Value("${recommendation.email.minimum-grade:B}") String minimumGrade,
            @Value("${recommendation.email.maximum-attempts:3}") int maximumAttempts,
            @Value("${recommendation.email.frontend-recommendation-url:http://localhost:5173/dashboard}") String frontendRecommendationUrl,
            @Value("${recommendation.email.from-address:no-reply@techadvisor.local}") String fromAddress) {

        String normalisedGrade = minimumGrade.trim().toUpperCase();

        if (!GRADES.contains(normalisedGrade)) {
            throw new IllegalArgumentException("recommendation.email.minimum-grade must be A-F");
        }

        if (maximumAttempts < 1) {
            throw new IllegalArgumentException("recommendation.email.maximum-attempts must be at least 1");
        }

        this.eligibleVerdict = eligibleVerdict.trim();
        this.minimumGrade = normalisedGrade;
        this.maximumAttempts = maximumAttempts;
        this.frontendRecommendationUrl = frontendRecommendationUrl.trim();
        this.fromAddress = fromAddress.trim();
    }

    public boolean isEligible(String verdict, String grade) {
        if (!eligibleVerdict.equals(verdict) || grade == null) {
            return false;
        }

        String normalisedGrade = grade.trim().toUpperCase();

        if (!GRADES.contains(normalisedGrade)) {
            return false;
        }

        return GRADES.indexOf(normalisedGrade) <= GRADES.indexOf(minimumGrade);
    }

    public int maximumAttempts() {
        return maximumAttempts;
    }

    public String frontendRecommendationUrl() {
        return frontendRecommendationUrl;
    }

    public String fromAddress() {
        return fromAddress;
    }
}