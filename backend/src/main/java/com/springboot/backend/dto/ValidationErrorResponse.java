package com.springboot.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        description = "Map from each invalid request field to its validation message.",
        type = "object",
        example = "{\"email\":\"Email format is invalid\"}",
        additionalProperties = Schema.AdditionalPropertiesValue.TRUE,
        additionalPropertiesSchema = String.class)
public final class ValidationErrorResponse {

    private ValidationErrorResponse() {
    }
}
