package com.springboot.backend.controller;

import com.springboot.backend.config.OpenApiConfig;
import com.springboot.backend.dto.SmartphoneCatalogueResponse;
import com.springboot.backend.service.SmartphoneCatalogueService;
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
@RequestMapping("/api/catalogue/smartphones")
@Tag(name = "Smartphone catalogue")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class SmartphoneCatalogueController {

    private final SmartphoneCatalogueService catalogue;

    public SmartphoneCatalogueController(SmartphoneCatalogueService catalogue) {
        this.catalogue = catalogue;
    }

    @GetMapping
    @Operation(summary = "List smartphone catalogue entries")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Smartphones ordered by brand and model",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = SmartphoneCatalogueResponse.class)))),
            @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content)
    })
    public List<SmartphoneCatalogueResponse> list() {
        return catalogue.list();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a smartphone catalogue entry")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Smartphone found",
                    content = @Content(schema = @Schema(implementation = SmartphoneCatalogueResponse.class))),
            @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content),
            @ApiResponse(responseCode = "404", description = "Smartphone not found", content = @Content)
    })
    public SmartphoneCatalogueResponse get(@PathVariable Long id) {
        return catalogue.get(id);
    }
}
