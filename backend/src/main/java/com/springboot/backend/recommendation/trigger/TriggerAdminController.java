package com.springboot.backend.recommendation.trigger;

import com.springboot.backend.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual firing of the recommendation triggers, for demos and recovery. Both
 * queue the run and return {@code 202} at once; the result lands in
 * {@code recommendations} and one {@code system_log} row. Safe to press
 * repeatedly: re-running a pair supersedes its previous {@code ACTIVE} row.
 */
@RestController
@RequestMapping("/api/admin/triggers")
@Tag(name = "Admin recommendation triggers", description = "Re-run recommendation triggers. Requires an ADMIN bearer JWT.")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class TriggerAdminController {

    private final RecommendationTriggerService service;

    public TriggerAdminController(RecommendationTriggerService service) {
        this.service = service;
    }

    @Schema(name = "TriggerQueuedResponse", description = "A trigger run accepted for background execution.")
    public record QueuedResponse(
            @Schema(example = "MARKET_EVENT") String trigger,
            @Schema(example = "42") long targetId,
            @Schema(example = "QUEUED") String status) {}

    @PostMapping("/market-events/{marketEventId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "Re-run the market-event trigger",
            description = "Re-evaluates every current device in the event product's category against that product.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Run queued",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = QueuedResponse.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content),
            @ApiResponse(responseCode = "403", description = "Caller does not have the ADMIN role", content = @Content),
            @ApiResponse(responseCode = "404", description = "Market event not found", content = @Content)
    })
    public QueuedResponse rerunMarketEvent(
            @Parameter(description = "market_events.id", example = "42") @PathVariable long marketEventId) {
        service.queueMarketEvent(marketEventId);
        return new QueuedResponse(TriggerRun.Kind.MARKET_EVENT.name(), marketEventId, "QUEUED");
    }

    @PostMapping("/user-devices/{userDeviceId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "Re-run the device-inventory trigger",
            description = "Re-evaluates one owned device against its whole shortlist. Only that device's recommendations change.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Run queued",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = QueuedResponse.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content),
            @ApiResponse(responseCode = "403", description = "Caller does not have the ADMIN role", content = @Content),
            @ApiResponse(responseCode = "404", description = "Owned device not found", content = @Content)
    })
    public QueuedResponse rerunDevice(
            @Parameter(description = "user_devices.id", example = "9") @PathVariable long userDeviceId) {
        service.queueDevice(userDeviceId);
        return new QueuedResponse(TriggerRun.Kind.DEVICE_INVENTORY.name(), userDeviceId, "QUEUED");
    }
}
