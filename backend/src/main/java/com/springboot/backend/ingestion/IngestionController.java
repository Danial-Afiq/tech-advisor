package com.springboot.backend.ingestion;

import java.net.URI;
import java.security.Principal;
import java.time.Clock;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.dao.DataAccessException;
import com.springboot.backend.ingestion.searchapi.SearchApiRepository;
import com.springboot.backend.ingestion.searchapi.SearchApiClient;
import com.springboot.backend.ingestion.searchapi.SearchApiSource;
import com.springboot.backend.ingestion.searchapi.ProductName;
import com.springboot.backend.ingestion.searchapi.ProductMatcher;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/ingestion")
public class IngestionController {
    private final IngestionOrchestrator runner;
    private final RunStore store;
    private final SourceRegistry registry;
    private final IngestionSettings settings;
    private final SearchApiRepository products;
    private final SearchApiClient searchApi;
    private final Clock clock;
    public IngestionController(IngestionOrchestrator runner, RunStore store, SourceRegistry registry,
                               IngestionSettings settings, SearchApiRepository products,
                               SearchApiClient searchApi, Clock clock) {
        this.runner = runner; this.store = store; this.registry = registry; this.settings = settings;
        this.products = products; this.searchApi = searchApi; this.clock = clock;
    }
    public record Request(List<String> sources, String reason, String productName, String externalProductId) {}
    public record CandidateRequest(String productName) {}
    @GetMapping("/session")
    public Map<String, String> session(Principal user, CsrfToken csrf) {
        return Map.of("username", user.getName(), "csrfHeader", csrf.getHeaderName(), "csrfToken", csrf.getToken());
    }
    @PostMapping("/searchapi/candidates")
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
            var candidates = ProductMatcher.candidates(requested.brand(), requested.model(),
                    searchApi.shopping(context, requested.canonicalName()));
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
    public ResponseEntity<RunLog> start(@RequestBody Request request, @RequestHeader("Idempotency-Key") String key, Principal user) {
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
            var matches = products.namedProducts(name.toLowerCase(Locale.ROOT));
            if (matches.size() > 1)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "More than one verified smartphone matches that name. Include the brand and full model name.");
            if (!matches.isEmpty()) {
                var product = matches.getFirst();
                target = new RunLog.ProductTarget(product.id(), product.name(), externalProductId);
            } else {
                if (!products.namedCatalogueProducts(name.toLowerCase(Locale.ROOT)).isEmpty())
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "That catalogue product is not an eligible verified smartphone.");
                try {
                    var requested = ProductName.parse(name);
                    target = new RunLog.ProductTarget(null, requested.canonicalName(), externalProductId);
                } catch (IllegalArgumentException invalid) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Enter both the smartphone brand and full model name.");
                }
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
    @GetMapping("/runs/{id}") public RunLog get(@PathVariable String id) { return store.get(id); }
    @GetMapping("/runs")
    public List<RunLog> history(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size,
                               @RequestParam(defaultValue="") String status, @RequestParam(defaultValue="") String trigger) {
        return store.history(page, size, status, trigger);
    }
    @GetMapping("/schedule")
    public Map<String, Object> schedule() {
        var state = store.state();
        var response = new LinkedHashMap<String, Object>();
        response.put("enabled", settings.schedulingEnabled()); response.put("intervalDays", RunStore.INTERVAL.toDays());
        response.put("intervalHours", RunStore.INTERVAL.toHours());
        response.put("anchor", state.anchor); response.put("nextScheduledAt", state.nextDue); response.put("activeRunId", state.activeRunId);
        return response;
    }
    @GetMapping("/sources")
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
