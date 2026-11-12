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

@Schema(description = "A price observation entered with a new catalogue smartphone; appended to price_history. "
        + "Storage and/or RAM make it a SKU-level price on a phone_variants row; both blank is a model-level price.")
public record SmartphonePriceRequest(
        @Schema(description = "Day the price was observed; stored as midnight UTC. At most one day ahead of today (UTC).")
        @NotNull LocalDate observedOn,
        @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal price,
        @Schema(description = "Three-letter ISO 4217 code.", example = "SGD", pattern = "[A-Z]{3}")
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "Currency must be a 3-letter ISO code") String currency,
        @Schema(description = "Variant storage in GB; blank with ramGb blank means a model-level price.")
        @Positive Integer storageGb,
        @Schema(description = "Variant RAM in GB.")
        @Positive Integer ramGb,
        @Schema(description = "Where the price came from; blank is stored as \"admin\".")
        @Size(max = 200) String source
) {}
