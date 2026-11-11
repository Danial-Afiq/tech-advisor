package com.springboot.backend.dto;

import com.springboot.backend.model.Gpu;
import com.springboot.backend.model.Product;
import com.springboot.backend.repository.LatestPrice;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Schema(description = "A GPU catalogue entry backed by products and gpu.")
public record GpuCatalogueResponse(
        Long id,
        String brand,
        String modelName,
        LocalDate releaseDate,
        String status,
        Integer baseClockMhz,
        Integer boostClockMhz,
        BigDecimal vramGb,
        String memoryType,
        Integer memoryBusBits,
        Integer tdpWatts,
        String architecture,
        @Schema(description = "Where the current values came from, e.g. seed:gpus.v2, techspot-gpu-specs or admin.")
        String source,
        String sourceUrl,
        OffsetDateTime fetchedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        @Schema(description = "Latest price_history observation, or null when the product has none.")
        BigDecimal latestPrice,
        @Schema(description = "ISO 4217 currency of latestPrice, or null.")
        String currency,
        @Schema(description = "Product image URL, or null when the catalogue has none.")
        String imageUrl
) {
    public GpuCatalogueResponse(Product product, Gpu gpu) {
        this(product.getId(), product.getBrand(), product.getModelName(), product.getReleaseDate(),
                product.getStatus(), gpu.getBaseClockMhz(), gpu.getBoostClockMhz(), gpu.getVramGb(),
                gpu.getMemoryType(), gpu.getMemoryBusBits(), gpu.getTdpWatts(), gpu.getArchitecture(),
                gpu.getSource(), gpu.getSourceUrl(), gpu.getFetchedAt(), product.getCreatedAt(),
                product.getUpdatedAt(), null, null, product.getImageUrl());
    }

    /** This entry with its latest price, or unchanged when there is none. */
    public GpuCatalogueResponse withLatestPrice(LatestPrice price) {
        if (price == null) {
            return this;
        }
        return new GpuCatalogueResponse(id, brand, modelName, releaseDate, status, baseClockMhz,
                boostClockMhz, vramGb, memoryType, memoryBusBits, tdpWatts, architecture, source,
                sourceUrl, fetchedAt, createdAt, updatedAt, price.getPrice(), price.getCurrency(), imageUrl);
    }
}
