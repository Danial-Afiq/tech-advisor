package com.springboot.backend.email;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class RecommendationEmailContentBuilder {

    private final RecommendationEmailSettings settings;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    public RecommendationEmailContentBuilder(RecommendationEmailSettings settings) {
        this.settings = settings;
    }

    public RecommendationEmailContent build(EmailRecommendation recommendation) {
        String subject = "Tech Advisor: strong upgrade candidate — " + recommendation.productName();
        String factors = topFactors(recommendation.factorAnalysis());
        String price = formatPrice(recommendation.price(), recommendation.currency());
        String reasoning = recommendation.reasoning() == null || recommendation.reasoning().isBlank()
                ? "No additional explanation was provided."
                : recommendation.reasoning().trim();
        String recommendationUrl = recommendationUrl(recommendation.recommendationId());

        String body = """
                Tech Advisor found a strong upgrade candidate for you.

                Product: %s
                Verdict: %s
                Owner-evidence grade: %s
                Latest observed price: %s

                Top contributing factors:
                %s

                Why this was recommended:
                %s

                This is an assessment based on available specifications and owner evidence, not an instruction to buy.

                Review or dismiss this recommendation:
                %s
                """.formatted(
                recommendation.productName(),
                readable(recommendation.verdict()),
                recommendation.grade(),
                price,
                factors,
                reasoning,
                recommendationUrl
        );

        return new RecommendationEmailContent(subject, body);
    }

    @SuppressWarnings("unchecked")
    private String topFactors(String factorAnalysis) {
        if (factorAnalysis == null || factorAnalysis.isBlank()) {
            return "- No contributing factors were available.";
        }

        try {
            Map<String, Object> root = json.readValue(factorAnalysis, Map.class);
            Object deterministicValue = root.get("deterministic");

            if (!(deterministicValue instanceof Map<?, ?> deterministic)) {
                return "- No contributing factors were available.";
            }

            List<String> factors = deterministic.entrySet().stream()
                    .filter(entry -> entry.getValue() instanceof Map<?, ?>)
                    .sorted(Comparator.comparingInt((Map.Entry<?, ?> entry) -> priority((Map<?, ?>) entry.getValue())).reversed())
                    .limit(3)
                    .map(entry -> formatFactor(entry.getKey().toString(), (Map<?, ?>) entry.getValue()))
                    .toList();

            return factors.isEmpty()
                    ? "- No contributing factors were available."
                    : String.join(System.lineSeparator(), factors);
        } catch (RuntimeException exception) {
            return "- Contributing factors could not be displayed.";
        }
    }

    private int priority(Map<?, ?> details) {
        Object priority = details.get("priority");
        return priority instanceof Number number ? number.intValue() : 0;
    }

    private String formatFactor(String factor, Map<?, ?> details) {
        Object impactValue = details.get("impact");
    String impact = impactValue == null ? "UNKNOWN" : impactValue.toString();
        return "- " + readable(factor) + ": " + readable(impact);
    }

    private String formatPrice(BigDecimal price, String currency) {
        if (price == null) {
            return "Not available";
        }

        String code = currency == null || currency.isBlank() ? "" : currency.trim() + " ";
        return code + price.stripTrailingZeros().toPlainString();
    }

    private String recommendationUrl(long recommendationId) {
        String separator = settings.frontendRecommendationUrl().contains("?") ? "&" : "?";
        return settings.frontendRecommendationUrl() + separator + "recommendation=" + recommendationId;
    }

    private String readable(String value) {
        if (value == null || value.isBlank()) {
            return "Not available";
        }

        String normalised = value.trim().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(normalised.charAt(0)) + normalised.substring(1);
    }
}