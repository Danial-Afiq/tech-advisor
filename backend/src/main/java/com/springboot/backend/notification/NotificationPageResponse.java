package com.springboot.backend.notification;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "One page of the authenticated user's notifications, newest first")
public record NotificationPageResponse(
        List<NotificationResponse> items,
        @Schema(example = "0") int page,
        @Schema(example = "10") int size,
        @Schema(example = "23") long totalItems,
        @Schema(example = "3") int totalPages
) {
}
