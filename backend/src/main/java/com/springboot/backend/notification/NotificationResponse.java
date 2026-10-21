package com.springboot.backend.notification;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "One in-app notification for the authenticated user")
public record NotificationResponse(

        @Schema(example = "7")
        Long notificationId,

        @Schema(
                description = "Notification kind",
                example = NotificationService.TYPE_NEW_RECOMMENDATION
        )
        String type,

        @Schema(example = "New upgrade recommendations!")
        String title,

        @Schema(example = "We found 3 new or updated upgrade options for your iPhone 13. "
                + "See what's worth upgrading to.")
        String message,

        @Schema(
                description = "Recommendation to open (the best one in the group). "
                        + "Follows re-assessments; null once the recommendation was deleted.",
                example = "42",
                nullable = true
        )
        Long recommendationId,

        @Schema(
                description = "Whether that recommendation is still on the user's dashboard",
                example = "true"
        )
        boolean available,

        @Schema(description = "Whether the user has read this notification", example = "false")
        boolean read,

        OffsetDateTime createdAt
) {
}
