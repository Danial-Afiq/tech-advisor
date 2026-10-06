package com.springboot.backend.controller;

import com.springboot.backend.config.OpenApiConfig;
import com.springboot.backend.dto.SmartphoneCatalogueRequest;
import com.springboot.backend.dto.SmartphoneCatalogueResponse;
import com.springboot.backend.service.SmartphoneCatalogueService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/catalogue/smartphones")
@Tag(name = "Admin smartphone catalogue")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class AdminSmartphoneCatalogueController {

    private final SmartphoneCatalogueService catalogue;

    public AdminSmartphoneCatalogueController(SmartphoneCatalogueService catalogue) {
        this.catalogue = catalogue;
    }

    @PostMapping
    @Operation(summary = "Create a smartphone catalogue entry")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Smartphone created",
                    content = @Content(schema = @Schema(implementation = SmartphoneCatalogueResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content),
            @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content),
            @ApiResponse(responseCode = "403", description = "ADMIN role required", content = @Content),
            @ApiResponse(responseCode = "409", description = "Brand and model already exist", content = @Content)
    })
    public ResponseEntity<SmartphoneCatalogueResponse> create(
            @Valid @RequestBody SmartphoneCatalogueRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogue.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace a smartphone catalogue entry")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Smartphone updated",
                    content = @Content(schema = @Schema(implementation = SmartphoneCatalogueResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content),
            @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content),
            @ApiResponse(responseCode = "403", description = "ADMIN role required", content = @Content),
            @ApiResponse(responseCode = "404", description = "Smartphone not found", content = @Content),
            @ApiResponse(responseCode = "409", description = "Brand and model already exist", content = @Content)
    })
    public SmartphoneCatalogueResponse update(
            @PathVariable Long id,
            @Valid @RequestBody SmartphoneCatalogueRequest request) {
        return catalogue.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a smartphone catalogue entry")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Smartphone deleted", content = @Content),
            @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content),
            @ApiResponse(responseCode = "403", description = "ADMIN role required", content = @Content),
            @ApiResponse(responseCode = "404", description = "Smartphone not found", content = @Content)
    })
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        catalogue.delete(id);
        return ResponseEntity.noContent().build();
    }
}
