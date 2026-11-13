package com.springboot.backend.email;

import java.math.BigDecimal;

public record EmailRecommendation(
        long recommendationId,
        long userId,
        long candidateProductId,
        String recipient,
        boolean accountEnabled,
        boolean recommendationEmailsEnabled,
        String productName,
        String verdict,
        String grade,
        BigDecimal price,
        String currency,
        BigDecimal upgradeScore,
        String factorAnalysis,
        String reasoning
) {
}
