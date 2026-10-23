package com.springboot.backend.recommendation;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("recommendation.dismissal")
@Validated
public record RecommendationDismissalSettings(

        @Min(
                value = 1,
                message =
                        "Recommendation dismissal cooldown must "
                                + "be at least one day"
        )
        int cooldownDays
) {
}