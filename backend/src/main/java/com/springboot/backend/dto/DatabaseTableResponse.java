package com.springboot.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "One table of the application database schema, for the admin overview.")
public record DatabaseTableResponse(
        String name,
        long rowCount,
        List<Column> columns
) {

    @Schema(description = "One column of a table. `references` names the target table of a foreign key, else null.")
    public record Column(
            String name,
            String type,
            boolean nullable,
            boolean primaryKey,
            String references
    ) {}
}
