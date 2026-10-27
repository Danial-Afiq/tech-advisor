package com.springboot.backend.dto;

import jakarta.validation.constraints.NotNull;

public record EmailNotificationPreferenceRequest(
        @NotNull(message = "Email notification preference is required")
        Boolean enabled
) {
}