package com.springboot.backend.ingestion;

import com.springboot.backend.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.dao.DataAccessException;

@RestController
@RequestMapping("/api/admin/ingestion")
@Tag(
        name = "Admin ingestion",
        description = "ADMIN-only ingestion operations. Production access remains disabled; Basic authentication is available only with the ingestion-demo profile.")
@SecurityRequirement(name = OpenApiConfig.DEMO_BASIC_AUTH)
public class IngestionController {
    private final IngestionOrchestrator runner;
    private final RunStore store;
    private final SourceRegistry registry;
    private final IngestionSettings settings;
    public IngestionController(IngestionOrchestrator runner, RunStore store, SourceRegistry registry, IngestionSettings settings) {
        this.runner = runner; this.store = store; this.registry = registry; this.settings = settings;
    }
    @Schema(description = "Manual ingestion request.")
    public record Request(
            @ArraySchema(
                    arraySchema = @Schema(description = "Enabled source IDs; empty selects every enabled source."),
                    schema = @Schema(example = "simulated-prices"))
            List<String> sources,
            @Schema(description = "Optional audit reason.", example = "Week 7 demonstration", maxLength = 500)
            String reason) {}

    @Schema(name = "IngestionSessionResponse", description = "Authenticated demo identity and CSRF details.")
    public record SessionResponse(
            @Schema(example = "demo-admin") String username,
            @Schema(example = "X-CSRF-TOKEN") String csrfHeader,
            @Schema(example = "csrf-token-value") String csrfToken) {}

    @Schema(name = "IngestionScheduleResponse", description = "Persistent ingestion schedule state.")
    public record ScheduleResponse(
            boolean enabled,
            @Schema(example = "1") long intervalDays,
            @Schema(example = "24") long intervalHours,
            Instant anchor,
            Instant nextScheduledAt,
            String activeRunId) {}

    @Schema(name = "IngestionSourceResponse", description = "Registered ingestion source and cooldown state.")
    public record SourceResponse(
            @Schema(example = "simulated-prices") String sourceId,
            boolean enabled,
            boolean simulation,
            Instant nextAllowedAt) {}

    @Schema(name = "IngestionErrorResponse", description = "Rejected ingestion operation.")
    public record ErrorResponse(
            @Schema(example = "Invalid request: check sources, pagination, reason and idempotency key")
            String message) {}

    @GetMapping("/session")
    @Operation(summary = "Read the demo identity and CSRF token", description = "Save the session cookie and send the returned CSRF header/token when starting a run.")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Authenticated demo session",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = SessionResponse.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content),
            @ApiResponse(responseCode = "403", description = "Production access disabled or caller is not an admin", content = @Content)
    })
    public Map<String, String> session(Principal user, CsrfToken csrf) {
        return Map.of("username", user.getName(), "csrfHeader", csrf.getHeaderName(), "csrfToken", csrf.getToken());
    }
    @PostMapping("/runs")
    @Operation(
            summary = "Start an asynchronous ingestion run",
            description = "Admits a manual run without moving the recurring schedule.",
            parameters = @Parameter(
                    name = "X-CSRF-TOKEN",
                    description = "Token returned by GET /api/admin/ingestion/session.",
                    required = true,
                    in = ParameterIn.HEADER))
    @ApiResponses({
            @ApiResponse(
                    responseCode = "202",
                    description = "Run admitted; Location identifies the run resource",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = RunLog.class))),
            @ApiResponse(responseCode = "400", description = "Invalid source selection, reason, or idempotency key", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content),
            @ApiResponse(responseCode = "403", description = "Invalid CSRF token, production access disabled, or caller is not an admin", content = @Content),
            @ApiResponse(responseCode = "409", description = "Another run is active or the idempotency key was reused with a different body", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Ingestion storage is unavailable", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<RunLog> start(
            @RequestBody Request request,
            @Parameter(
                    description = "Actor-scoped idempotency key (8-128 letters, digits, underscores, or hyphens).",
                    required = true,
                    example = "week7-demo-01",
                    schema = @Schema(pattern = "^[A-Za-z0-9_-]{8,128}$"))
            @RequestHeader("Idempotency-Key") String key,
            Principal user) {
        var run = runner.manual(request.sources(), user.getName(), key, request.reason());
        return ResponseEntity.accepted().location(URI.create("/api/admin/ingestion/runs/" + run.runId)).body(run);
    }
    @GetMapping("/runs/{id}")
    @Operation(summary = "Get an ingestion run")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Persisted or in-flight run state", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RunLog.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content),
            @ApiResponse(responseCode = "403", description = "Production access disabled or caller is not an admin", content = @Content),
            @ApiResponse(responseCode = "404", description = "Run ID was not found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Ingestion storage is unavailable", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public RunLog get(
            @Parameter(description = "Run identifier", example = "4f90b1cb-2ef0-46bf-aebc-a72be946fbcd")
            @PathVariable String id) { return store.get(id); }
    @GetMapping("/runs")
    @Operation(summary = "List ingestion run history", description = "Returns the newest runs first.")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Run history; an empty array is returned past the final page",
                    content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = RunLog.class)))),
            @ApiResponse(responseCode = "400", description = "Pagination or filter value is invalid", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content),
            @ApiResponse(responseCode = "403", description = "Production access disabled or caller is not an admin", content = @Content),
            @ApiResponse(responseCode = "503", description = "Ingestion storage is unavailable", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public List<RunLog> history(
            @Parameter(description = "Zero-based page number", example = "0", schema = @Schema(minimum = "0", maximum = "10000"))
            @RequestParam(defaultValue="0") int page,
            @Parameter(description = "Page size", example = "20", schema = @Schema(minimum = "1", maximum = "100"))
            @RequestParam(defaultValue="20") int size,
            @Parameter(description = "Optional run-status filter")
            @RequestParam(defaultValue="") String status,
            @Parameter(description = "Optional trigger filter", schema = @Schema(allowableValues = {"", "MANUAL", "SCHEDULED"}))
            @RequestParam(defaultValue="") String trigger) {
        return store.history(page, size, status, trigger);
    }
    @GetMapping("/schedule")
    @Operation(summary = "Get ingestion schedule state")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Persistent schedule and active-run state",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ScheduleResponse.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content),
            @ApiResponse(responseCode = "403", description = "Production access disabled or caller is not an admin", content = @Content),
            @ApiResponse(responseCode = "503", description = "Ingestion storage is unavailable", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public Map<String, Object> schedule() {
        var state = store.state();
        var response = new LinkedHashMap<String, Object>();
        response.put("enabled", settings.schedulingEnabled()); response.put("intervalDays", RunStore.INTERVAL.toDays());
        response.put("intervalHours", RunStore.INTERVAL.toHours());
        response.put("anchor", state.anchor); response.put("nextScheduledAt", state.nextDue); response.put("activeRunId", state.activeRunId);
        return response;
    }
    @GetMapping("/sources")
    @Operation(summary = "List registered ingestion sources")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Source availability and cooldown state",
                    content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = SourceResponse.class)))),
            @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content),
            @ApiResponse(responseCode = "403", description = "Production access disabled or caller is not an admin", content = @Content),
            @ApiResponse(responseCode = "503", description = "Ingestion storage is unavailable", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public List<Map<String, Object>> sources() {
        var state = store.state();
        return registry.all().stream().map(source -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceId", source.sourceId()); row.put("enabled", registry.enabled(source.sourceId()));
            row.put("simulation", source.simulation()); row.put("nextAllowedAt", state.nextAllowed.get(source.sourceId()));
            return row;
        }).toList();
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String,String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", "Invalid request: check sources, pagination, reason and idempotency key"));
    }
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    ResponseEntity<Map<String,String>> rejected(org.springframework.web.server.ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", Objects.requireNonNullElse(e.getReason(), "Request rejected")));
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String,String>> unavailable() {
        return ResponseEntity.status(503).body(Map.of("message", "Ingestion storage unavailable; no new run was admitted"));
    }
}
