package com.springboot.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Schema(description = "One benchmark_results observation of a catalogue smartphone.")
public record CatalogueBenchmarkResponse(
        Long id,
        String benchmarkName,
        BigDecimal score,
        String unit,
        boolean higherIsBetter,
        String source,
        OffsetDateTime observedAt
) {}
