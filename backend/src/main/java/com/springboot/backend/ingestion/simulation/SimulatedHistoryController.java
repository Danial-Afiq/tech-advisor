package com.springboot.backend.ingestion.simulation;

import com.springboot.backend.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN-only simulated history/change endpoints; absent unless the local demo profile is active. */
@RestController
@Profile("ingestion-demo")
@RequestMapping("/api/admin/simulation")
@Tag(name = "Admin simulation", description = "Local demo-only market history and change injection.")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class SimulatedHistoryController {

    private final SimulatedHistoryService service;
    private final SimulatedMarketChangeService changes;

    public SimulatedHistoryController(
            SimulatedHistoryService service,
            SimulatedMarketChangeService changes) {
        this.service = service;
        this.changes = changes;
    }

    public record HistoryRequest(
            @Positive @Schema(example = "812") long productId,
            @NotNull @Schema(example = "2026-01-01T00:00:00Z") OffsetDateTime startAt,
            @Min(2) @Max(100) @Schema(example = "12") int observationCount,
            @Min(1) @Max(365) @Schema(example = "30") int intervalDays,
            @NotNull @Schema(example = "2037") Long seed,
            @NotNull @DecimalMin("0.00") @DecimalMax("30.00")
            @Schema(example = "15.00", description = "Maximum price movement from the genuine baseline, percent.")
            BigDecimal maxPriceVariationPercent,
            @NotNull @DecimalMin("0.00") @DecimalMax("5.00")
            @Schema(example = "3.00", description = "Maximum benchmark movement from the genuine baseline, percent.")
            BigDecimal maxBenchmarkVariationPercent) {}

    public record PriceChangeRequest(
            @Positive @Schema(example = "812") long productId,
            @NotNull @DecimalMin(value = "0.00", inclusive = false) @Schema(example = "899.00")
            BigDecimal newPrice,
            @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._:-]+")
            @Schema(example = "demo-price-drop-1") String idempotencyKey) {}

    public record BenchmarkChangeRequest(
            @Positive @Schema(example = "812") long productId,
            @NotBlank @Size(max = 200) @Schema(example = "Geekbench 6 Multi-Core") String benchmarkName,
            @NotNull @DecimalMin(value = "0.00", inclusive = false) @Schema(example = "8500")
            BigDecimal newScore,
            @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._:-]+")
            @Schema(example = "demo-benchmark-update-1") String idempotencyKey) {}

    @PostMapping("/history")
    @Operation(
            summary = "Generate simulated price and benchmark history",
            description = "Available only with the ingestion-demo profile and a loopback PostgreSQL URL. "
                    + "Writes older, source-labelled observations for an existing VERIFIED smartphone or GPU. "
                    + "It does not emit market events or re-run recommendations.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "History generated",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = SimulatedHistoryService.Result.class))),
            @ApiResponse(responseCode = "200", description = "Identical scenario already exists",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = SimulatedHistoryService.Result.class))),
            @ApiResponse(responseCode = "400", description = "Request validation failed", content = @Content),
            @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content),
            @ApiResponse(responseCode = "403", description = "ADMIN role required", content = @Content),
            @ApiResponse(responseCode = "404", description = "Product not found", content = @Content),
            @ApiResponse(responseCode = "409", description = "No baseline or unsafe timestamp range",
                    content = @Content)
    })
    public ResponseEntity<SimulatedHistoryService.Result> generate(
            @Valid @RequestBody HistoryRequest request) {

        var result = service.generate(new SimulatedHistoryService.Command(
                request.productId(),
                request.startAt().toInstant(),
                request.observationCount(),
                Duration.ofDays(request.intervalDays()),
                request.seed(),
                request.maxPriceVariationPercent().movePointLeft(2),
                request.maxBenchmarkVariationPercent().movePointLeft(2)));
        return ResponseEntity.status(result.alreadyGenerated() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(result);
    }

    @PostMapping("/changes/price")
    @Operation(
            summary = "Inject a simulated current price change",
            description = "Atomically appends the latest price, records a PRICE_CHANGE event, and lets the "
                    + "existing after-commit recommendation trigger re-evaluate affected devices.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Change and event created"),
            @ApiResponse(responseCode = "200", description = "Identical idempotent request already applied"),
            @ApiResponse(responseCode = "400", description = "Request validation failed", content = @Content),
            @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content),
            @ApiResponse(responseCode = "403", description = "ADMIN role required", content = @Content),
            @ApiResponse(responseCode = "404", description = "Product not found", content = @Content),
            @ApiResponse(responseCode = "409", description = "No baseline, unchanged value, or conflicting key",
                    content = @Content)
    })
    public ResponseEntity<SimulatedMarketChangeService.Result> injectPrice(
            @Valid @RequestBody PriceChangeRequest request) {

        var result = changes.injectPrice(new SimulatedMarketChangeService.PriceCommand(
                request.productId(), request.newPrice(), request.idempotencyKey()));
        return changeResponse(result);
    }

    @PostMapping("/changes/benchmark")
    @Operation(
            summary = "Inject a simulated current benchmark change",
            description = "Atomically appends the latest named benchmark, records a BENCHMARK_UPDATE event, "
                    + "and lets the existing after-commit recommendation trigger re-evaluate affected devices.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Change and event created"),
            @ApiResponse(responseCode = "200", description = "Identical idempotent request already applied"),
            @ApiResponse(responseCode = "400", description = "Request validation failed", content = @Content),
            @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content),
            @ApiResponse(responseCode = "403", description = "ADMIN role required", content = @Content),
            @ApiResponse(responseCode = "404", description = "Product not found", content = @Content),
            @ApiResponse(responseCode = "409", description = "No baseline, unchanged value, or conflicting key",
                    content = @Content)
    })
    public ResponseEntity<SimulatedMarketChangeService.Result> injectBenchmark(
            @Valid @RequestBody BenchmarkChangeRequest request) {

        var result = changes.injectBenchmark(new SimulatedMarketChangeService.BenchmarkCommand(
                request.productId(), request.benchmarkName(), request.newScore(), request.idempotencyKey()));
        return changeResponse(result);
    }

    private static ResponseEntity<SimulatedMarketChangeService.Result> changeResponse(
            SimulatedMarketChangeService.Result result) {
        return ResponseEntity.status(result.alreadyApplied() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(result);
    }
}
