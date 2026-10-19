package com.springboot.backend.controller;

import com.springboot.backend.config.OpenApiConfig;
import com.springboot.backend.dto.DatabaseTableResponse;
import com.springboot.backend.service.DatabaseSchemaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/database")
@Tag(name = "Admin database overview")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class AdminDatabaseController {

    private final DatabaseSchemaService schema;

    public AdminDatabaseController(DatabaseSchemaService schema) {
        this.schema = schema;
    }

    @GetMapping("/tables")
    @Operation(summary = "List every database table with its columns, keys and row count")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tables in the current schema",
                    content = @Content(array = @ArraySchema(
                            schema = @Schema(implementation = DatabaseTableResponse.class)))),
            @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content),
            @ApiResponse(responseCode = "403", description = "ADMIN role required", content = @Content)
    })
    public List<DatabaseTableResponse> tables() {
        return schema.tables();
    }
}
