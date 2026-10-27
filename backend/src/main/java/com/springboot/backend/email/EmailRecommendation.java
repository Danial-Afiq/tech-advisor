package com.springboot.backend.email;

import java.math.BigDecimal;

public record EmailRecommendation(
        long recommendationId,
        long userId,
        String recipient,
        boolean accountEnabled,
        boolean recommendationEmailsEnabled,
        String productName,
        String verdict,
        String grade,
        BigDecimal price,
        String currency,
        String factorAnalysis,
        String reasoning
) {
}