package com.springboot.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Schema(
        description =
                "Summary of an active recommendation "
                        + "for one of the authenticated user's devices"
)
public record DashboardRecommendationResponse(

        @Schema(example = "42")
        Long recommendationId,

        @Schema(example = "15")
        Long currentDeviceId,

        @Schema(example = "My current phone")
        String currentDeviceName,

        @Schema(example = "27")
        Long candidateProductId,

        @Schema(example = "Samsung")
        String candidateBrand,

        @Schema(example = "Galaxy S26")
        String candidateModelName,

        @Schema(
                description =
                        "Most recently observed candidate price",
                example = "1299.00"
        )
        BigDecimal latestPrice,

        @Schema(example = "SGD")
        String currency,

        @Schema(example = "STRONG_UPGRADE_CANDIDATE")
        String verdict,

        @Schema(example = "A")
        String confidence,

        @Schema(
                description =
                        "Persisted explanation for the recommendation"
        )
        String reasoning,

        OffsetDateTime createdAt
) {
}