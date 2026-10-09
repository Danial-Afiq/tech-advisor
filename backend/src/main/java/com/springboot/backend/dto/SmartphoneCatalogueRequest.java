package com.springboot.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description = "Common product fields and smartphone specifications.")
public record SmartphoneCatalogueRequest(
        @NotBlank @Size(max = 100) String brand,
        @NotBlank @Size(max = 200) String modelName,
        LocalDate releaseDate,
        @NotBlank @Pattern(regexp = "VERIFIED|UNVERIFIED") String status,
        @Size(max = 200) String chipset,
        @Positive Integer ramGb,
        @Positive @Digits(integer = 3, fraction = 3) BigDecimal cpuGhz,
        @Positive Integer storageGb,
        @Positive Integer batteryMah,
        @Positive Integer wiredChargingWatts,
        @Positive Integer wirelessChargingWatts,
        @Positive @Digits(integer = 3, fraction = 3) BigDecimal displaySizeInches,
        @Positive Integer refreshRateHz,
        @Positive Integer weightG,
        @Size(max = 500) String cameraSpecs,
        @Positive Integer pixelDensity,
        @Size(max = 50) String ipRating,
        @Size(max = 100) String os,
        @Positive @Digits(integer = 3, fraction = 2) BigDecimal softwareSupportYears
) {}
