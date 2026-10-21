package com.springboot.backend.controller;

import com.springboot.backend.config.OpenApiConfig;
import com.springboot.backend.dto.CatalogueBenchmarkResponse;
import com.springboot.backend.dto.CataloguePriceResponse;
import com.springboot.backend.service.CatalogueHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/catalogue/smartphones/{id}")
@Tag(name = "Admin smartphone catalogue")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class AdminCatalogueHistoryController {

    private final CatalogueHistoryService history;

    public AdminCatalogueHistoryController(CatalogueHistoryService history) {
        this.history = history;
    }

    @GetMapping("/prices")
    @Operation(summary = "List a smartphone's price observations, newest first (at most 50)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Price history",
                    content = @Content(array = @ArraySchema(
                            schema = @Schema(implementation = CataloguePriceResponse.class)))),
            @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content),
            @ApiResponse(responseCode = "403", description = "ADMIN role required", content = @Content),
            @ApiResponse(responseCode = "404", description = "Smartphone not found", content = @Content)
    })
    public List<CataloguePriceResponse> prices(@PathVariable Long id) {
        return history.prices(id);
    }

    @GetMapping("/benchmarks")
    @Operation(summary = "List a smartphone's benchmark results, newest first (at most 50)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Benchmark results",
                    content = @Content(array = @ArraySchema(
                            schema = @Schema(implementation = CatalogueBenchmarkResponse.class)))),
            @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content),
            @ApiResponse(responseCode = "403", description = "ADMIN role required", content = @Content),
            @ApiResponse(responseCode = "404", description = "Smartphone not found", content = @Content)
    })
    public List<CatalogueBenchmarkResponse> benchmarks(@PathVariable Long id) {
        return history.benchmarks(id);
    }
}
