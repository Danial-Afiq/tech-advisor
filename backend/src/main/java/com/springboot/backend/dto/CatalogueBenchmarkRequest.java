package com.springboot.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description = "A benchmark result entered with a new catalogue smartphone; appended to benchmark_results. "
        + "Unit and higher_is_better are copied from the latest stored result with the same name "
        + "(case-insensitive, which also adopts its spelling); a new name is stored with no unit, higher is better.")
public record CatalogueBenchmarkRequest(
        @Schema(example = "Geekbench 6 CPU Multi-Core")
        @NotBlank @Size(max = 100) String benchmarkName,
        @NotNull @Positive BigDecimal score,
        @Schema(description = "Day the result was observed; stored as midnight UTC. At most one day ahead of today (UTC).")
        @NotNull LocalDate observedOn,
        @Schema(description = "Where the result came from; blank is stored as \"admin\".")
        @Size(max = 200) String source
) {}
