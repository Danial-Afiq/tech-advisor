package com.springboot.backend.ingestion;

import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

@Component
public class RunStore {
    public static final Duration INTERVAL = Duration.ofDays(14);
    public static final Duration LEASE = Duration.ofSeconds(90);
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private final Clock clock;
    private final IngestionSettings settings;

    public RunStore(JdbcTemplate db, PlatformTransactionManager manager, Clock clock, IngestionSettings settings) {
        this.db = db; this.tx = new TransactionTemplate(manager); this.clock = clock; this.settings = settings;
    }

    private <T> T locked(Function<CoordinatorState, T> action) {
        return tx.execute(status -> {
            db.update("INSERT INTO system_log(component,status,message,metadata) VALUES "
                    + "('INGESTION_COORDINATOR','READY','Reserved scheduler state','{}') ON CONFLICT DO NOTHING");
            var state = json.readValue(db.queryForObject("SELECT metadata::text FROM system_log "
                    + "WHERE component='INGESTION_COORDINATOR' FOR UPDATE", String.class), CoordinatorState.class);
            if (state.anchor == null && settings.anchor() != null) {
                state.anchor = settings.anchor(); state.nextDue = state.anchor;
            }
            T result = action.apply(state);
            db.update("UPDATE system_log SET metadata=?::jsonb WHERE component='INGESTION_COORDINATOR'",
                    json.writeValueAsString(state));
            return result;
        });
    }

    public CoordinatorState state() { return locked(state -> state); }

    public RunLog admit(List<String> ids, String actor, String key, String reason, boolean scheduled,
                        boolean simulation) {
        return admit(ids, actor, key, reason, scheduled, simulation, null);
    }

    public RunLog admit(List<String> ids, String actor, String key, String reason, boolean scheduled,
                        boolean simulation, RunLog.ProductTarget product) {
        return locked(state -> {
            Instant now = clock.instant();
            recover(state, now);
            if (!scheduled) {
                var existing = db.query("SELECT metadata::text FROM system_log WHERE component='INGESTION_RUN' "
                        + "AND metadata->>'requestedBy'=? AND metadata->>'idempotencyKey'=?",
                        (rs, row) -> json.readValue(rs.getString(1), RunLog.class), actor, key);
                if (!existing.isEmpty()) {
                    var run = existing.getFirst();
                    if (!run.sourceIds.equals(ids) || !Objects.equals(run.reason, reason)
                            || !sameProduct(run.product, product))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key used for a different request");
                    return run;
                }
            }
            if (scheduled && (state.nextDue == null || now.isBefore(state.nextDue))) return null;
            if (state.activeRunId != null) {
                if (scheduled) return null;
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Active run: " + state.activeRunId);
            }
            var run = new RunLog();
            run.runId = UUID.randomUUID().toString(); run.sourceIds = new ArrayList<>(ids);
            run.triggerType = scheduled ? "SCHEDULED" : "MANUAL";
            run.requestedBy = actor; run.reason = reason; run.idempotencyKey = key;
            run.requestedAt = now; run.simulation = simulation;
            run.product = product;
            if (scheduled) {
                run.missedSlots = Duration.between(state.nextDue, now).dividedBy(INTERVAL);
                run.scheduledFor = state.nextDue.plus(INTERVAL.multipliedBy(run.missedSlots));
                state.nextDue = run.scheduledFor.plus(INTERVAL);
            }
            db.update("INSERT INTO system_log(component,status,message,metadata) VALUES "
                    + "('INGESTION_RUN',?,'Ingestion execution',?::jsonb)", run.status, json.writeValueAsString(run));
            state.activeRunId = run.runId; state.owner = null; state.leaseUntil = now.plus(LEASE);
            return run;
        });
    }

    private boolean sameProduct(RunLog.ProductTarget first, RunLog.ProductTarget second) {
        if (first == null || second == null) return first == second;
        return Objects.equals(first.productName(), second.productName())
                && Objects.equals(first.externalProductId(), second.externalProductId())
                && (Objects.equals(first.productId(), second.productId())
                    || first.productId() == null || second.productId() == null);
    }

    public RunLog claim(String owner) {
        return locked(state -> {
            Instant now = clock.instant(); recover(state, now);
            if (state.activeRunId == null || state.owner != null) return null;
            var run = get(state.activeRunId);
            state.owner = owner; state.leaseUntil = now.plus(LEASE);
            run.status = "RUNNING"; run.startedAt = now;
            run.latenessMs = run.scheduledFor == null ? 0 : Duration.between(run.scheduledFor, now).toMillis();
            write(run); return run;
        });
    }

    public void heartbeat(String runId, String owner) {
        locked(state -> { check(state, runId, owner); state.leaseUntil = clock.instant().plus(LEASE); return null; });
    }

    public boolean startSource(String runId, String owner, IngestionSource source) {
        return locked(state -> {
            check(state, runId, owner);
            Instant now = clock.instant();
            if (now.isBefore(state.nextAllowed.getOrDefault(source.sourceId(), Instant.MIN))) return false;
            state.nextAllowed.remove(source.sourceId()); return true;
        });
    }

    public void progress(RunLog run, String owner) {
        locked(state -> { check(state, run.runId, owner); write(run); return null; });
    }

    public void deferSource(String runId, String owner, String sourceId, Instant until) {
        locked(state -> {
            check(state, runId, owner);
            if (!until.isAfter(clock.instant())) state.nextAllowed.remove(sourceId);
            else state.nextAllowed.merge(sourceId, until, (a, b) -> a.isAfter(b) ? a : b);
            return null;
        });
    }

    public void finish(RunLog run, String owner) {
        locked(state -> {
            check(state, run.runId, owner);
            run.finishedAt = clock.instant();
            run.durationMs = Duration.between(run.startedAt, run.finishedAt).toMillis();
            write(run); clear(state); return null;
        });
    }

    private void check(CoordinatorState state, String runId, String owner) {
        if (!Objects.equals(state.activeRunId, runId) || !Objects.equals(state.owner, owner)
                || state.leaseUntil == null || !clock.instant().isBefore(state.leaseUntil))
            throw new IllegalStateException("Ingestion ownership lost");
    }

    private void recover(CoordinatorState state, Instant now) {
        if (state.activeRunId == null || state.leaseUntil == null || now.isBefore(state.leaseUntil)) return;
        var run = get(state.activeRunId);
        if (state.owner == null) { // Accepted work has never dispatched; it is safe to claim later.
            state.leaseUntil = now.plus(LEASE); return;
        }
        run.status = "INTERRUPTED"; run.finishedAt = now;
        run.durationMs = run.startedAt == null ? 0 : Duration.between(run.startedAt, now).toMillis();
        for (var source : run.sources) if ("RUNNING".equals(source.status)) {
            source.status = "INTERRUPTED"; source.finishedAt = now;
        }
        write(run); clear(state);
    }
    private void clear(CoordinatorState state) { state.activeRunId = null; state.owner = null; state.leaseUntil = null; }
    private void write(RunLog run) {
        db.update("UPDATE system_log SET status=?,metadata=?::jsonb WHERE component='INGESTION_RUN' "
                + "AND metadata->>'runId'=?", run.status, json.writeValueAsString(run), run.runId);
    }
    public RunLog get(String id) {
        var rows = db.query("SELECT metadata::text FROM system_log WHERE component='INGESTION_RUN' "
                + "AND metadata->>'runId'=?", (rs, row) -> json.readValue(rs.getString(1), RunLog.class), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found");
        return rows.getFirst();
    }
    public List<RunLog> history(int page, int size, String status, String trigger) {
        if (page < 0 || page > 10000 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid pagination");
        return db.query("SELECT metadata::text FROM system_log WHERE component='INGESTION_RUN' "
                + "AND (?='' OR status=?) AND (?='' OR metadata->>'triggerType'=?) "
                + "ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                (rs, row) -> json.readValue(rs.getString(1), RunLog.class), status, status, trigger, trigger, size, page * size);
    }
}
