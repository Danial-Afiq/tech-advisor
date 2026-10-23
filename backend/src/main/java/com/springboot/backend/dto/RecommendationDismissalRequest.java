package com.springboot.backend.dto;

import com.springboot.backend.recommendation.RecommendationDismissalReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(
        description =
                "Reason and optional explanation for dismissing "
                        + "a recommendation"
)
public record RecommendationDismissalRequest(

        @NotNull(message = "Dismissal reason is required")
        @Schema(
                description = "Structured reason for the dismissal",
                example = "TOO_EXPENSIVE",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        RecommendationDismissalReason reason,

        @Size(
                max = 1000,
                message =
                        "Dismissal comment must not exceed 1000 characters"
        )
        @Schema(
                description =
                        "Optional additional explanation from the user",
                example =
                        "This costs more than I want to spend."
        )
        String comment
) {
}