package com.springboot.backend.marketevent;

import com.springboot.backend.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin entry of market events: one of the two ingestion paths for them, and the
 * demo control for injecting a change live. Recording an event that names a
 * product re-evaluates every affected owned device in the background.
 */
@RestController
@RequestMapping("/api/admin/market-events")
@Tag(name = "Admin market events", description = "Record market changes. Requires an ADMIN bearer JWT.")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class MarketEventController {

    static final String ADMIN_SOURCE = "admin";

    private final MarketEventService service;

    public MarketEventController(MarketEventService service) {
        this.service = service;
    }

    @Schema(name = "MarketEventRequest", description = "A market change entered by an administrator.")
    public record Request(
            @Schema(description = "Product the change concerns. Without one, no recommendation is re-evaluated.",
                    example = "17", nullable = true)
            Long productId,
            @NotNull MarketEventType eventType,
            @NotBlank @Size(max = 300)
            @Schema(example = "Galaxy S25 drops to S$1099")
            String title,
            @Size(max = 2000) String description,
            @Schema(example = "{\"price\": 1199}") Map<String, Object> oldValue,
            @Schema(example = "{\"price\": 1099}") Map<String, Object> newValue) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Record a market event",
            description = "Persists the event. When it names a product, recommendations for every current device "
                    + "in that product's category are re-evaluated asynchronously after the response.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Event recorded",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = MarketEvent.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request body", content = @Content),
            @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content),
            @ApiResponse(responseCode = "403", description = "Caller does not have the ADMIN role", content = @Content),
            @ApiResponse(responseCode = "404", description = "Product not found", content = @Content)
    })
    public MarketEvent record(@Valid @RequestBody Request request) {
        return service.record(new MarketEventService.NewMarketEvent(
                request.productId(),
                request.eventType(),
                request.title().trim(),
                request.description(),
                request.oldValue(),
                request.newValue(),
                ADMIN_SOURCE));
    }
}
