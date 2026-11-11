package com.springboot.backend.controller;

import com.springboot.backend.config.OpenApiConfig;
import com.springboot.backend.dto.GpuCatalogueResponse;
import com.springboot.backend.service.GpuCatalogueService;
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

/** Read-only GPU catalogue for signed-in users, e.g. the device form's model search. */
@RestController
@RequestMapping("/api/catalogue/gpus")
@Tag(name = "GPU catalogue")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class GpuCatalogueController {

    private final GpuCatalogueService catalogue;

    public GpuCatalogueController(GpuCatalogueService catalogue) {
        this.catalogue = catalogue;
    }

    @GetMapping
    @Operation(summary = "List GPU catalogue entries")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "GPUs ordered by brand and model",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = GpuCatalogueResponse.class)))),
            @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content)
    })
    public List<GpuCatalogueResponse> list() {
        return catalogue.list();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a GPU catalogue entry")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "GPU found",
                    content = @Content(schema = @Schema(implementation = GpuCatalogueResponse.class))),
            @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content),
            @ApiResponse(responseCode = "404", description = "GPU not found", content = @Content)
    })
    public GpuCatalogueResponse get(@PathVariable Long id) {
        return catalogue.get(id);
    }
}
