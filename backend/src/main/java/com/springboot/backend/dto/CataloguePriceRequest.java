package com.springboot.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description = "A price observation entered with a new catalogue GPU; appended to price_history.")
public record CataloguePriceRequest(
        @Schema(description = "Day the price was observed; stored as midnight UTC. At most one day ahead of today (UTC).")
        @NotNull LocalDate observedOn,
        @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal price,
        @Schema(description = "Three-letter ISO 4217 code.", example = "SGD", pattern = "[A-Z]{3}")
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "Currency must be a 3-letter ISO code") String currency,
        @Schema(description = "Where the price came from; blank is stored as \"admin\".")
        @Size(max = 200) String source
) {}
