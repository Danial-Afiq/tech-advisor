package com.springboot.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description = "Common product fields and GPU specifications. Blank specs are stored as unknown (null).")
public record GpuCatalogueRequest(
        @NotBlank @Size(max = 100) String brand,
        @NotBlank @Size(max = 200) String modelName,
        LocalDate releaseDate,
        @NotBlank @Pattern(regexp = "VERIFIED|UNVERIFIED") String status,
        @Positive Integer baseClockMhz,
        @Positive Integer boostClockMhz,
        @Schema(description = "Video memory in GB.") @Positive @Digits(integer = 4, fraction = 5) BigDecimal vramGb,
        @Size(max = 50) String memoryType,
        @Schema(description = "Memory bus width in bits.") @Positive Integer memoryBusBits,
        @Positive Integer tdpWatts,
        @Size(max = 100) String architecture
) {}
