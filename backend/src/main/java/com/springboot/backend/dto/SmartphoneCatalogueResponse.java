package com.springboot.backend.dto;

import com.springboot.backend.model.Phone;
import com.springboot.backend.model.Product;
import com.springboot.backend.repository.LatestPrice;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Schema(description = "A smartphone catalogue entry backed by products and phone.")
public record SmartphoneCatalogueResponse(
        Long id,
        String brand,
        String modelName,
        LocalDate releaseDate,
        String status,
        String chipset,
        Integer ramGb,
        BigDecimal cpuGhz,
        Integer storageGb,
        Integer batteryMah,
        Integer wiredChargingWatts,
        Integer wirelessChargingWatts,
        BigDecimal displaySizeInches,
        Integer refreshRateHz,
        Integer weightG,
        String cameraSpecs,
        Integer pixelDensity,
        String ipRating,
        String os,
        BigDecimal softwareSupportYears,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        @Schema(description = "Latest price_history observation, or null when the product has none.")
        BigDecimal latestPrice,
        @Schema(description = "ISO 4217 currency of latestPrice, or null.")
        String currency
) {
    public SmartphoneCatalogueResponse(Product product, Phone phone) {
        this(
                product.getId(),
                product.getBrand(),
                product.getModelName(),
                product.getReleaseDate(),
                product.getStatus(),
                phone.getChipset(),
                phone.getRamGb(),
                phone.getCpuGhz(),
                phone.getStorageGb(),
                phone.getBatteryMah(),
                phone.getWiredChargingWatts(),
                phone.getWirelessChargingWatts(),
                phone.getDisplaySizeInches(),
                phone.getRefreshRateHz(),
                phone.getWeightG(),
                phone.getCameraSpecs(),
                phone.getPixelDensity(),
                phone.getIpRating(),
                phone.getOs(),
                phone.getSoftwareSupportYears(),
                product.getCreatedAt(),
                product.getUpdatedAt(),
                null,
                null
        );
    }

    /** This entry with its latest price, or unchanged when there is none. */
    public SmartphoneCatalogueResponse withLatestPrice(LatestPrice price) {
        if (price == null) {
            return this;
        }
        return new SmartphoneCatalogueResponse(id, brand, modelName, releaseDate, status,
                chipset, ramGb, cpuGhz, storageGb, batteryMah, wiredChargingWatts,
                wirelessChargingWatts, displaySizeInches, refreshRateHz, weightG, cameraSpecs,
                pixelDensity, ipRating, os, softwareSupportYears, createdAt, updatedAt,
                price.getPrice(), price.getCurrency());
    }
}
