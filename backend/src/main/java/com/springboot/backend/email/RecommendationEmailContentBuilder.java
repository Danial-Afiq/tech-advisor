package com.springboot.backend.email;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class RecommendationEmailContentBuilder {

    private static final String DISCLAIMER =
            "This assessment uses available specifications and owner evidence. It is not an instruction to buy.";

    private final RecommendationEmailSettings settings;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    public RecommendationEmailContentBuilder(RecommendationEmailSettings settings) {
        this.settings = settings;
    }

    public RecommendationEmailContent build(EmailRecommendation recommendation) {
        String subject = "Tech Advisor: strong upgrade candidate — "
                + oneLine(productName(recommendation));
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

        return new RecommendationEmailContent(
                subject,
                body,
                htmlDocument(
                        "A strong upgrade candidate for you",
                        "We found a phone that may be a meaningful upgrade for your current device.",
                        recommendationCardHtml(recommendation, null)));
    }

    public RecommendationEmailContent buildSummary(List<EmailRecommendation> recommendations) {
        if (recommendations.isEmpty()) {
            throw new IllegalArgumentException("A recommendation summary cannot be empty");
        }

        StringBuilder entries = new StringBuilder();
        for (int index = 0; index < recommendations.size(); index++) {
            EmailRecommendation recommendation = recommendations.get(index);
            String reasoning = recommendation.reasoning() == null || recommendation.reasoning().isBlank()
                    ? "No additional explanation was provided."
                    : recommendation.reasoning().trim();

            if (index > 0) {
                entries.append(System.lineSeparator()).append(System.lineSeparator());
            }
            entries.append("%d. %s%n".formatted(index + 1, recommendation.productName()))
                    .append("   Verdict: ").append(readable(recommendation.verdict())).append(System.lineSeparator())
                    .append("   Owner-evidence grade: ").append(recommendation.grade()).append(System.lineSeparator())
                    .append("   Upgrade score: ").append(formatScore(recommendation.upgradeScore()))
                    .append(System.lineSeparator())
                    .append("   Latest observed price: ")
                    .append(formatPrice(recommendation.price(), recommendation.currency()))
                    .append(System.lineSeparator())
                    .append("   Top contributing factors:").append(System.lineSeparator())
                    .append(indent(topFactors(recommendation.factorAnalysis()))).append(System.lineSeparator())
                    .append("   Why this was recommended: ").append(reasoning).append(System.lineSeparator())
                    .append("   Review or dismiss: ")
                    .append(recommendationUrl(recommendation.recommendationId()));
        }

        String body = """
                Tech Advisor found these strongest upgrade candidates for your device.

                %s

                This is an assessment based on available specifications and owner evidence, not an instruction to buy.
                """.formatted(entries);

        String htmlCards = java.util.stream.IntStream.range(0, recommendations.size())
                .mapToObj(index -> recommendationCardHtml(recommendations.get(index), index + 1))
                .collect(java.util.stream.Collectors.joining());

        return new RecommendationEmailContent(
                "Tech Advisor: your top upgrade recommendations",
                body,
                htmlDocument(
                        "Your top upgrade recommendations",
                        "These are the strongest eligible matches from your latest device assessment.",
                        htmlCards));
    }

    private String htmlDocument(String title, String intro, String cards) {
        return """
                <!doctype html>
                <html lang="en">
                  <body style="margin:0;padding:0;background:#f3f6fb;color:#172033;">
                    <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0"
                           style="width:100%%;background:#f3f6fb;">
                      <tr>
                        <td align="center" style="padding:24px 12px;">
                          <table role="presentation" width="640" cellspacing="0" cellpadding="0" border="0"
                                 style="width:100%%;max-width:640px;background:#ffffff;border-radius:18px;overflow:hidden;box-shadow:0 12px 34px rgba(8,16,31,.12);">
                            <tr>
                              <td style="padding:28px 30px;background:#08101f;color:#eef5ff;">
                                <div style="font:700 14px Arial,sans-serif;letter-spacing:.08em;color:#a998ff;">✦ TECH ADVISOR</div>
                                <h1 style="margin:10px 0 8px;font:700 28px/1.2 Arial,sans-serif;color:#ffffff;">%s</h1>
                                <p style="margin:0;font:400 15px/1.6 Arial,sans-serif;color:#b9c6d8;">%s</p>
                              </td>
                            </tr>
                            <tr>
                              <td style="padding:24px 24px 8px;">%s</td>
                            </tr>
                            <tr>
                              <td style="padding:8px 30px 28px;">
                                <p style="margin:0;padding-top:18px;border-top:1px solid #e7ebf2;font:400 12px/1.6 Arial,sans-serif;color:#6c778b;">%s</p>
                              </td>
                            </tr>
                          </table>
                        </td>
                      </tr>
                    </table>
                  </body>
                </html>
                """.formatted(html(title), html(intro), cards, html(DISCLAIMER));
    }

    private String recommendationCardHtml(EmailRecommendation recommendation, Integer rank) {
        String product = productName(recommendation);
        String image = validImageUrl(recommendation.imageUrl())
                .map(url -> """
                        <td width="112" valign="top" style="width:112px;padding-right:18px;">
                          <img src="%s" width="96" height="96" alt="%s"
                               style="display:block;width:96px;height:96px;object-fit:contain;border:1px solid #e7ebf2;border-radius:14px;background:#f7f9fc;">
                        </td>
                        """.formatted(html(url), html(product + " product image")))
                .orElse("""
                        <td width="72" valign="top" style="width:72px;padding-right:18px;">
                          <div style="width:56px;height:56px;border-radius:14px;background:#f0edff;text-align:center;font:30px/56px Arial,sans-serif;">📱</div>
                        </td>
                        """);
        String factors = topFactors(recommendation.factorAnalysis()).lines()
                .map(line -> line.startsWith("- ") ? line.substring(2) : line)
                .map(line -> "<span style=\"display:inline-block;margin:0 6px 6px 0;padding:6px 9px;border-radius:999px;background:#f0edff;font:600 12px Arial,sans-serif;color:#5b43c7;\">"
                        + html(line) + "</span>")
                .collect(java.util.stream.Collectors.joining());
        String recommendationUrl = recommendationUrl(recommendation.recommendationId());
        String rankLabel = rank == null ? "RECOMMENDED FOR YOU" : "TOP PICK " + rank;

        return """
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0"
                       style="width:100%%;margin:0 0 16px;border:1px solid #e1e6ef;border-radius:16px;background:#ffffff;">
                  <tr>
                    <td style="padding:20px;">
                      <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" style="width:100%%;">
                        <tr>
                          %s
                          <td valign="top">
                            <div style="font:700 11px Arial,sans-serif;letter-spacing:.1em;color:#7c5cff;">%s</div>
                            <h2 style="margin:6px 0 8px;font:700 21px/1.25 Arial,sans-serif;color:#172033;">%s</h2>
                            <span style="display:inline-block;padding:6px 10px;border-radius:999px;background:#fff0f2;font:700 12px Arial,sans-serif;color:#c23c4d;">%s</span>
                            <span style="display:inline-block;margin-left:6px;padding:6px 10px;border-radius:999px;background:#edf9f5;font:700 12px Arial,sans-serif;color:#147a5a;">Owner evidence %s</span>
                          </td>
                        </tr>
                      </table>
                      <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0"
                             style="width:100%%;margin-top:18px;background:#f7f9fc;border-radius:12px;">
                        <tr>
                          <td width="50%%" style="padding:13px 14px;border-right:1px solid #e1e6ef;">
                            <div style="font:600 11px Arial,sans-serif;color:#7b8799;">INDICATIVE PRICE</div>
                            <div style="margin-top:4px;font:700 17px Arial,sans-serif;color:#5b43c7;">%s</div>
                          </td>
                          <td width="50%%" style="padding:13px 14px;">
                            <div style="font:600 11px Arial,sans-serif;color:#7b8799;">UPGRADE SCORE</div>
                            <div style="margin-top:4px;font:700 17px Arial,sans-serif;color:#172033;">%s</div>
                          </td>
                        </tr>
                      </table>
                      <div style="margin-top:17px;">
                        <div style="margin-bottom:7px;font:700 12px Arial,sans-serif;color:#4b5669;">Top contributing factors</div>
                        %s
                      </div>
                      <div style="margin-top:12px;padding:14px;border-left:3px solid #7c5cff;border-radius:8px;background:#f8f7ff;">
                        <div style="margin-bottom:5px;font:700 12px Arial,sans-serif;color:#4b5669;">Why this was recommended</div>
                        <div style="font:400 14px/1.65 Arial,sans-serif;color:#4f5b6f;">%s</div>
                      </div>
                      <div style="margin-top:18px;">
                        <a href="%s" style="display:inline-block;padding:11px 16px;border-radius:10px;background:#7c5cff;font:700 14px Arial,sans-serif;color:#ffffff;text-decoration:none;">Review recommendation</a>
                      </div>
                    </td>
                  </tr>
                </table>
                """.formatted(
                image,
                html(rankLabel),
                html(product),
                html(readable(recommendation.verdict())),
                html(displayGrade(recommendation.grade())),
                html(formatPrice(recommendation.price(), recommendation.currency())),
                html(formatScore(recommendation.upgradeScore())),
                factors,
                htmlWithLineBreaks(reasoning(recommendation)),
                html(recommendationUrl));
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

            // v4 scoring stores factor details inside "factors"; legacy rows may be flat.
            Object nested = deterministic.get("factors");
            Map<?, ?> perFactor = nested instanceof Map<?, ?> entries ? entries : deterministic;
            List<String> factors = perFactor.entrySet().stream()
                    .filter(entry -> entry.getValue() instanceof Map<?, ?>)
                    .sorted(Comparator.comparingDouble(
                            (Map.Entry<?, ?> entry) -> significance((Map<?, ?>) entry.getValue()))
                            .reversed().thenComparing(entry -> entry.getKey().toString()))
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

    private double significance(Map<?, ?> details) {
        Object contribution = details.get("contribution");
        if (contribution instanceof Number number && Double.isFinite(number.doubleValue())) {
            return Math.abs(number.doubleValue());
        }
        return priority(details);
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

    private String formatScore(BigDecimal score) {
        return score == null
                ? "Not available"
                : score.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%";
    }

    private String indent(String value) {
        return value.lines().map(line -> "   " + line).collect(java.util.stream.Collectors.joining(
                System.lineSeparator()));
    }

    private String recommendationUrl(long recommendationId) {
        String separator = settings.frontendRecommendationUrl().contains("?") ? "&" : "?";
        return settings.frontendRecommendationUrl() + separator + "recommendation=" + recommendationId;
    }

    private java.util.Optional<String> validImageUrl(String value) {
        if (value == null || value.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            if (uri.getHost() == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(uri.toASCIIString());
        } catch (IllegalArgumentException exception) {
            return java.util.Optional.empty();
        }
    }

    private String productName(EmailRecommendation recommendation) {
        return recommendation.productName() == null || recommendation.productName().isBlank()
                ? "Suggested product"
                : recommendation.productName().trim();
    }

    private String displayGrade(String grade) {
        return grade == null || grade.isBlank() ? "Not available" : grade.trim();
    }

    private String reasoning(EmailRecommendation recommendation) {
        return recommendation.reasoning() == null || recommendation.reasoning().isBlank()
                ? "No AI explanation is available for this recommendation."
                : recommendation.reasoning().trim();
    }

    private String htmlWithLineBreaks(String value) {
        return html(value).replace("\r\n", "<br>").replace("\r", "<br>").replace("\n", "<br>");
    }

    private String html(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String oneLine(String value) {
        return value.replace('\r', ' ').replace('\n', ' ');
    }

    private String readable(String value) {
        if (value == null || value.isBlank()) {
            return "Not available";
        }

        String normalised = value.trim().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(normalised.charAt(0)) + normalised.substring(1);
    }
}
