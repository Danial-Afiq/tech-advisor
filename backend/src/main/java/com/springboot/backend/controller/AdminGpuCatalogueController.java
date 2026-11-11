package com.springboot.backend.controller;

import com.springboot.backend.config.OpenApiConfig;
import com.springboot.backend.dto.GpuCatalogueRequest;
import com.springboot.backend.dto.GpuCatalogueResponse;
import com.springboot.backend.service.GpuCatalogueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ADMIN-only GPU catalogue. Unlike smartphones there are no USER read routes yet:
 * the admin catalogue page is the only consumer.
 */
@RestController
@RequestMapping("/api/admin/catalogue/gpus")
@Tag(name = "Admin GPU catalogue")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class AdminGpuCatalogueController {

    private final GpuCatalogueService catalogue;

    public AdminGpuCatalogueController(GpuCatalogueService catalogue) {
        this.catalogue = catalogue;
    }

    @GetMapping
    @Operation(summary = "List GPU catalogue entries, ordered by brand and model")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "GPUs"),
            @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content),
            @ApiResponse(responseCode = "403", description = "ADMIN role required", content = @Content)
    })
    public List<GpuCatalogueResponse> list() {
        return catalogue.list();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one GPU catalogue entry")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "GPU",
                    content = @Content(schema = @Schema(implementation = GpuCatalogueResponse.class))),
            @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content),
            @ApiResponse(responseCode = "403", description = "ADMIN role required", content = @Content),
            @ApiResponse(responseCode = "404", description = "GPU not found", content = @Content)
    })
    public GpuCatalogueResponse get(@PathVariable Long id) {
        return catalogue.get(id);
    }

    @PostMapping
    @Operation(summary = "Create a GPU catalogue entry")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "GPU created",
                    content = @Content(schema = @Schema(implementation = GpuCatalogueResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content),
            @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content),
            @ApiResponse(responseCode = "403", description = "ADMIN role required", content = @Content),
            @ApiResponse(responseCode = "409", description = "Brand and model already exist", content = @Content)
    })
    public ResponseEntity<GpuCatalogueResponse> create(@Valid @RequestBody GpuCatalogueRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogue.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace a GPU catalogue entry")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "GPU updated",
                    content = @Content(schema = @Schema(implementation = GpuCatalogueResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content),
            @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content),
            @ApiResponse(responseCode = "403", description = "ADMIN role required", content = @Content),
            @ApiResponse(responseCode = "404", description = "GPU not found", content = @Content),
            @ApiResponse(responseCode = "409", description = "Brand and model already exist", content = @Content)
    })
    public GpuCatalogueResponse update(@PathVariable Long id, @Valid @RequestBody GpuCatalogueRequest request) {
        return catalogue.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a GPU catalogue entry")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "GPU deleted", content = @Content),
            @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content),
            @ApiResponse(responseCode = "403", description = "ADMIN role required", content = @Content),
            @ApiResponse(responseCode = "404", description = "GPU not found", content = @Content)
    })
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        catalogue.delete(id);
        return ResponseEntity.noContent().build();
    }
}
