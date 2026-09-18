package com.springboot.backend.ingestion;

import java.net.URI;
import java.security.Principal;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.dao.DataAccessException;

@RestController
@RequestMapping("/api/admin/ingestion")
public class IngestionController {
    private final IngestionOrchestrator runner;
    private final RunStore store;
    private final SourceRegistry registry;
    private final IngestionSettings settings;
    public IngestionController(IngestionOrchestrator runner, RunStore store, SourceRegistry registry, IngestionSettings settings) {
        this.runner = runner; this.store = store; this.registry = registry; this.settings = settings;
    }
    public record Request(List<String> sources, String reason) {}
    @GetMapping("/session")
    public Map<String, String> session(Principal user, CsrfToken csrf) {
        return Map.of("username", user.getName(), "csrfHeader", csrf.getHeaderName(), "csrfToken", csrf.getToken());
    }
    @PostMapping("/runs")
    public ResponseEntity<RunLog> start(@RequestBody Request request, @RequestHeader("Idempotency-Key") String key, Principal user) {
        var run = runner.manual(request.sources(), user.getName(), key, request.reason());
        return ResponseEntity.accepted().location(URI.create("/api/admin/ingestion/runs/" + run.runId)).body(run);
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
