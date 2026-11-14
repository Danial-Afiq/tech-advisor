package com.springboot.backend.email;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationEmailContentBuilderTest {

    private final RecommendationEmailContentBuilder builder = new RecommendationEmailContentBuilder(
            new RecommendationEmailSettings(
                    "STRONG_UPGRADE_CANDIDATE",
                    "B",
                    3,
                    "http://localhost:5173/dashboard",
                    "no-reply@techadvisor.local"));

    @Test
    void htmlEscapesUntrustedTextAndRejectsUnsafeImageSchemes() {
        RecommendationEmailContent content = builder.build(recommendation(
                "Phone <script>alert('x')</script>",
                "javascript:alert(1)",
                "Useful upgrade <img src=x onerror=alert(1)>"));

        assertThat(content.html())
                .contains("Phone &lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;")
                .contains("Useful upgrade &lt;img src=x onerror=alert(1)&gt;")
                .doesNotContain("<script>", "javascript:alert(1)", "<img src=x");
    }

    @Test
    void validCatalogueImageIsRenderedWithAccessibleAlternativeText() {
        RecommendationEmailContent content = builder.build(recommendation(
                "Example Phone",
                "https://images.example/phone.png",
                "A useful upgrade."));

        assertThat(content.html())
                .contains("src=\"https://images.example/phone.png\"")
                .contains("alt=\"Example Phone product image\"");
    }

    private EmailRecommendation recommendation(String productName, String imageUrl, String reasoning) {
        return new EmailRecommendation(
                42L,
                7L,
                11L,
                "user@example.com",
                true,
                true,
                productName,
                imageUrl,
                "STRONG_UPGRADE_CANDIDATE",
                "A",
                new BigDecimal("1299.00"),
                "SGD",
                new BigDecimal("0.91"),
                "{\"deterministic\":{\"factors\":{}}}",
                reasoning);
    }
}
