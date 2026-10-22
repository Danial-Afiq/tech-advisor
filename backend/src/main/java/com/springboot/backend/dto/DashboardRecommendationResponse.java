package com.springboot.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

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

        @Schema(
                description =
                        "Whether the user has starred (saved) "
                                + "this recommendation",
                example = "false"
        )
        boolean starred,

        OffsetDateTime createdAt,

        @Schema(
                description =
                        "Numeric specs the deterministic verdict compared, from "
                                + "input_snapshot.computed.spec_deltas, biggest change first. "
                                + "deltaPct is direction-corrected with the verdict's own "
                                + "rules: positive always means the suggested product is "
                                + "better on that spec"
        )
        List<SpecChange> specChanges
) {
    /**
     * @param spec     the {@code phone} column (e.g. {@code battery_mah}), or
     *                 {@code benchmark_score}
     * @param deltaPct signed percentage change; positive means better
     */
    public record SpecChange(
            @Schema(example = "battery_mah") String spec,
            @Schema(example = "54.3") double deltaPct) {
    }
}