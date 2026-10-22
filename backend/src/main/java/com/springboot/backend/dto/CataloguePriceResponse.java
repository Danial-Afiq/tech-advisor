package com.springboot.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Schema(description = "One price_history observation of a catalogue smartphone.")
public record CataloguePriceResponse(
        Long id,
        BigDecimal price,
        String currency,
        String source,
        OffsetDateTime observedAt,
        @Schema(description = "The SKU the price was observed for, e.g. \"256 GB · 8 GB RAM\"; null for a model-level price.")
        String variant
) {}
