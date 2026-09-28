package com.springboot.backend.ingestion;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Schema(description = "Persistent audit state for an ingestion run.")
public class RunLog {
    @Schema(example = "4f90b1cb-2ef0-46bf-aebc-a72be946fbcd")
    public String runId;
    @Schema(allowableValues = {"MANUAL", "SCHEDULED"}, example = "MANUAL")
    public String triggerType;
    @Schema(example = "demo-admin")
    public String requestedBy;
    public String reason;
    public String idempotencyKey;
    @Schema(allowableValues = {"ACCEPTED", "RUNNING", "SUCCESS", "PARTIAL_FAILURE", "FAILED", "INTERRUPTED", "SKIPPED"})
    public String status = "ACCEPTED";
    public Instant requestedAt, scheduledFor, startedAt, finishedAt;
    public long durationMs, latenessMs, missedSlots;
    public int processedPayloadCount, duplicatePayloadCount, rejectedPayloadCount, errorCount, errorStackCount;
    public int skippedSourceCount;
    public boolean simulation;
    public List<String> sourceIds = new ArrayList<>();
    public List<SourceResult> sources = new ArrayList<>();

    @Schema(description = "Per-source execution result with sanitised failure details.")
    public static class SourceResult {
        public String sourceId;
        @Schema(allowableValues = {"RUNNING", "SUCCESS", "PARTIAL_FAILURE", "FAILED", "SKIPPED"})
        public String status = "RUNNING";
        public int processedPayloadCount, duplicatePayloadCount, rejectedPayloadCount, errorCount, errorStackCount;
        public Instant startedAt, finishedAt;
        // Only exception type and application frames, never messages/HTTP bodies/credentials.
        @ArraySchema(arraySchema = @Schema(description = "Exception type and at most five application stack frames; never messages, bodies, or credentials."))
        public List<String> errors = new ArrayList<>();
    }
}
