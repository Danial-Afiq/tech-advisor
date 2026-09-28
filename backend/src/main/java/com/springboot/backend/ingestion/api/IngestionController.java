package com.springboot.backend.ingestion.api;

import com.springboot.backend.config.OpenApiConfig;
import com.springboot.backend.ingestion.config.IngestionSettings;
import com.springboot.backend.ingestion.core.IngestionFailure;
import com.springboot.backend.ingestion.core.IngestionOrchestrator;
import com.springboot.backend.ingestion.core.SourceContext;
import com.springboot.backend.ingestion.core.SourceRegistry;
import com.springboot.backend.ingestion.searchapi.ProductMatcher;
import com.springboot.backend.ingestion.searchapi.ProductMatcher.ProductName;
import com.springboot.backend.ingestion.searchapi.SearchApiRepository;
import com.springboot.backend.ingestion.searchapi.SearchApiSource;
import com.springboot.backend.ingestion.run.RunLog;
import com.springboot.backend.ingestion.run.RunStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.security.Principal;
import java.time.Clock;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/ingestion")
@Tag(
        name = "Admin ingestion",
        description = "Ingestion operations protected by a bearer JWT whose account has the ADMIN role.")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class IngestionController {
    private final IngestionOrchestrator runner;
    private final RunStore store;
    private final SourceRegistry registry;
    private final IngestionSettings settings;
    private final SearchApiRepository products;
    private final SearchApiSource searchApi;
    private final Clock clock;
    public IngestionController(IngestionOrchestrator runner, RunStore store, SourceRegistry registry,
                               IngestionSettings settings, SearchApiRepository products,
                               SearchApiSource searchApi, Clock clock) {
        this.runner = runner; this.store = store; this.registry = registry; this.settings = settings;
        this.products = products; this.searchApi = searchApi; this.clock = clock;
    }
    @Schema(description = "Manual ingestion request.")
    public record Request(
            @ArraySchema(
                    arraySchema = @Schema(description = "Enabled source IDs; empty selects every enabled source."),
                    schema = @Schema(example = "searchapi-google-product-reviews"))
            List<String> sources,
            @Schema(description = "Optional audit reason.", example = "Refresh owner evidence", maxLength = 500)
            String reason,
            @Schema(
                    description = "Optional brand and full model name for a targeted SearchAPI review run.",
                    example = "Samsung Galaxy S25",
                    minLength = 1,
                    maxLength = 200)
            String productName,
            @Schema(
                    description = "SearchAPI product selected through the candidate endpoint; required with productName.",
                    example = "searchapi-product-id")
            String externalProductId) {}

    @Schema(name = "SearchApiCandidateRequest", description = "Smartphone name used to find validated SearchAPI product choices.")
    public record CandidateRequest(
            @Schema(example = "Samsung Galaxy S25", minLength = 1, maxLength = 200)
            String productName) {}

    @Schema(name = "IngestionSessionResponse", description = "Authenticated administrator identity.")
    public record SessionResponse(
            @Schema(example = "admin@example.com") String username) {}

    @Schema(name = "IngestionScheduleResponse", description = "Persistent ingestion schedule state.")
    public record ScheduleResponse(
            boolean enabled,
            @Schema(example = "2") long intervalDays,
            @Schema(example = "48") long intervalHours,
            java.time.Instant anchor,
            java.time.Instant nextScheduledAt,
            String activeRunId) {}

    @Schema(name = "IngestionSourceResponse", description = "Registered ingestion source and cooldown state.")
    public record SourceResponse(
            @Schema(example = "searchapi-google-product-reviews") String sourceId,
            boolean enabled,
            boolean simulation,
            java.time.Instant nextAllowedAt) {}

    @Schema(name = "IngestionErrorResponse", description = "Rejected ingestion operation.")
    public record ErrorResponse(
            @Schema(example = "Invalid request: check sources, pagination, reason and idempotency key")
            String message) {}
    // No CSRF token here: authentication is a bearer JWT the browser must set explicitly
    // (SecurityConfig disables CSRF protection app-wide), not an ambient cookie a
    // cross-site request could ride along on - there's nothing for CSRF to protect.
    @GetMapping("/session")
    @Operation(summary = "Read the authenticated admin identity")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Authenticated administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = SessionResponse.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content),
            @ApiResponse(responseCode = "403", description = "Caller does not have the ADMIN role", content = @Content)
    })
    public Map<String, String> session(Principal user) {
        return Map.of("username", user.getName());
    }
    @PostMapping("/searchapi/candidates")
    @Operation(
            summary = "Find SearchAPI product candidates",
            description = "Returns validated provider identities for an admin-targeted smartphone review run.")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Validated SearchAPI product choices",
                    content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ProductMatcher.Candidate.class)))),
            @ApiResponse(responseCode = "400", description = "SearchAPI is disabled, the name is invalid, or no match exists", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content),
            @ApiResponse(responseCode = "403", description = "Caller does not have the ADMIN role", content = @Content),
            @ApiResponse(responseCode = "429", description = "SearchAPI asked the caller to retry later", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "502", description = "SearchAPI returned an unusable response", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "SearchAPI or ingestion storage is unavailable", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public List<ProductMatcher.Candidate> candidates(@RequestBody CandidateRequest request) {
        if (!registry.enabled(SearchApiSource.ID))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SearchAPI ingestion is disabled.");
        String name = productName(request == null ? null : request.productName());
        ProductName requested;
        try { requested = ProductName.parse(name); }
        catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Enter both the smartphone brand and full model name.");
        }
        try (var context = new SourceContext(clock, () -> {})) {
            var candidates = searchApi.findCandidates(context, requested);
            if (candidates.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No matching smartphone was found. Check the brand and full model name.");
            return candidates;
        } catch (ResponseStatusException rejected) { throw rejected; }
        catch (SourceContext.RetryLater limited) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "SearchAPI is rate limited. Try finding products again later.");
        } catch (SourceContext.TransportFailure unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "SearchAPI could not be reached. Try finding products again.");
        } catch (IngestionFailure failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "SearchAPI could not return product choices. Try again later.");
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "SearchAPI product search failed. Try again later.");
        }
    }
    @PostMapping("/runs")
    @Operation(
            summary = "Start an asynchronous ingestion run",
            description = "Admits a manual run without moving the recurring schedule; SearchAPI runs may target an admin-selected product.")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "202",
                    description = "Run admitted; Location identifies the run resource",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = RunLog.class))),
            @ApiResponse(responseCode = "400", description = "Invalid source, product selection, reason, or idempotency key", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content),
            @ApiResponse(responseCode = "403", description = "Caller does not have the ADMIN role", content = @Content),
            @ApiResponse(responseCode = "409", description = "Another run is active or the idempotency key was reused with a different body", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Ingestion storage is unavailable", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<RunLog> start(
            @RequestBody Request request,
            @Parameter(
                    description = "Actor-scoped idempotency key (8-128 letters, digits, underscores, or hyphens).",
                    required = true,
                    example = "searchapi-reviews-01",
                    schema = @Schema(pattern = "^[A-Za-z0-9_-]{8,128}$"))
            @RequestHeader("Idempotency-Key") String key,
            Principal user) {
        var ids = registry.select(request.sources());
        RunLog.ProductTarget target = null;
        if (request.productName() != null) {
            if (!ids.contains(SearchApiSource.ID))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select SearchAPI customer reviews to specify a product.");
            String name = productName(request.productName());
            String externalProductId = request.externalProductId() == null ? "" : request.externalProductId().trim();
            if (externalProductId.isEmpty() || externalProductId.length() > 1000)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Find matching products and select one before starting ingestion.");
            var matches = products.namedProducts(name);
            if (matches.size() > 1)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "More than one verified smartphone matches that name. Include the brand and full model name.");
            if (!matches.isEmpty()) {
                var product = matches.getFirst();
                target = new RunLog.ProductTarget(product.id(), product.name(), externalProductId);
            } else if (!products.namedCatalogueProducts(name).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "That catalogue product is not an eligible verified smartphone.");
            } else {
                // MobileAPI is the sole source of truth for `products` rows - SearchAPI never
                // creates one, so an unmatched name fails closed rather than starting a run that
                // would otherwise invent a catalogue entry from an unverified admin-typed name.
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No matching smartphone found in the catalogue. Ingest this device via "
                                + "MobileAPI before requesting SearchAPI reviews for it.");
            }
        } else if (request.externalProductId() != null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A selected SearchAPI product requires a smartphone name.");
        var run = runner.manual(ids, user.getName(), key, request.reason(), target);
        return ResponseEntity.accepted().location(URI.create("/api/admin/ingestion/runs/" + run.runId)).body(run);
    }
    private String productName(String value) {
        String name = value == null ? "" : value.replaceAll("[\\p{Z}\\s]+", " ").trim();
        if (name.isEmpty() || name.length() > 200)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a product name of 1 to 200 characters.");
        return name;
    }
    @GetMapping("/runs/{id}")
    @Operation(summary = "Get an ingestion run")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Persisted or in-flight run state", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RunLog.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content),
            @ApiResponse(responseCode = "403", description = "Caller does not have the ADMIN role", content = @Content),
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
            @ApiResponse(responseCode = "403", description = "Caller does not have the ADMIN role", content = @Content),
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
            @ApiResponse(responseCode = "403", description = "Caller does not have the ADMIN role", content = @Content),
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
            @ApiResponse(responseCode = "403", description = "Caller does not have the ADMIN role", content = @Content),
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
