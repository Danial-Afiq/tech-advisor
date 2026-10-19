package com.springboot.backend.notification;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Number of unread notifications for the authenticated user")
public record UnreadCountResponse(@Schema(example = "2") long count) {
}
