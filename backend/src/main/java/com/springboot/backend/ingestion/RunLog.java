package com.springboot.backend.ingestion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class RunLog {
    public String runId, triggerType, requestedBy, reason, idempotencyKey;
    public String status = "ACCEPTED";
    public Instant requestedAt, scheduledFor, startedAt, finishedAt;
    public long durationMs, latenessMs, missedSlots;
    public int processedPayloadCount, duplicatePayloadCount, rejectedPayloadCount, errorCount, errorStackCount;
    public int skippedSourceCount;
    public boolean simulation;
    public List<String> sourceIds = new ArrayList<>();
    public List<SourceResult> sources = new ArrayList<>();

    public static class SourceResult {
        public String sourceId, status = "RUNNING";
        public int processedPayloadCount, duplicatePayloadCount, rejectedPayloadCount, errorCount, errorStackCount;
        public Instant startedAt, finishedAt;
        // Only exception type and application frames, never messages/HTTP bodies/credentials.
        public List<String> errors = new ArrayList<>();
    }
}
