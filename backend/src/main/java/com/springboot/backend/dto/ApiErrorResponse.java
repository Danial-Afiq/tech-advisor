package com.springboot.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "API error returned by application-level validation or lookup failures.")
public record ApiErrorResponse(
        @Schema(description = "Human-readable error message.", example = "Device not found")
        String error) {
}
