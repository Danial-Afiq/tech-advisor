package com.springboot.backend.recommendation.trigger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What one trigger run did, accumulated pair by pair and written as a single
 * {@code system_log} row when it finishes ({@link TriggerRunLog}).
 *
 * <p>Not thread-safe; a run lives on one trigger thread from start to finish.
 */
public final class TriggerRun {

    /** Detail lists are capped so a large run cannot produce an enormous log row. */
    static final int DETAIL_LIMIT = 20;

    public enum Kind {
        MARKET_EVENT("TRIGGER_MARKET_EVENT"),
        DEVICE_INVENTORY("TRIGGER_DEVICE_INVENTORY");

        final String component;

        Kind(String component) {
            this.component = component;
        }
    }

    public enum Status { SUCCESS, PARTIAL_SUCCESS, FAILURE, SKIPPED }

    /** How one (owned device, candidate) pair ended. */
    public enum PairOutcome {
        /** Failed the shortlist (budget, status, price, category); its rows for the device are deleted. */
        NOT_SHORTLISTED,
        /** Shortlisted but had no spec sheet; nothing written. */
        UNCLASSIFIABLE,
        /** {@code NO_MEANINGFUL_CHANGE}: deterministic row written, no model call. */
        REJECTED_EARLY,
        /** Past the gate, but the AI call was not made; the deterministic row stands. */
        AI_SKIPPED,
        /** Past the gate and assessed by the AI service. */
        ASSESSED,
        FAILED
    }

    private final Kind kind;
    private final String cause;
    private final long startedNanos = System.nanoTime();
    private final Map<String, Object> context = new LinkedHashMap<>();
    private final EnumMap<PairOutcome, Integer> outcomes = new EnumMap<>(PairOutcome.class);
    private final List<Map<String, Object>> failures = new ArrayList<>();
    private final List<Map<String, Object>> aiSkips = new ArrayList<>();
    private int pairsSelected;
    private int failureCount;
    private int rowsDeleted;
    private String skippedReason;

    TriggerRun(Kind kind, String cause) {
        this.kind = kind;
        this.cause = cause;
        for (PairOutcome outcome : PairOutcome.values()) {
            outcomes.put(outcome, 0);
        }
    }

    public Kind kind() { return kind; }

    TriggerRun context(String key, Object value) {
        context.put(key, value);
        return this;
    }

    void selected(int pairs) {
        pairsSelected += pairs;
    }

    void rowsDeleted(int rows) {
        rowsDeleted += rows;
    }

    void record(PairOutcome outcome) {
        outcomes.merge(outcome, 1, Integer::sum);
    }

    void aiSkipped(Long userDeviceId, Long candidateProductId, String reason) {
        record(PairOutcome.AI_SKIPPED);
        if (aiSkips.size() < DETAIL_LIMIT) {
            Map<String, Object> entry = pair(userDeviceId, candidateProductId);
            entry.put("reason", reason);
            aiSkips.add(entry);
        }
    }

    /**
     * Records a failure. With a candidate it is one failed pair; without one the
     * whole device failed before any pair was known.
     */
    void fail(Long userDeviceId, Long candidateProductId, String stage, Throwable error) {
        failureCount++;
        if (candidateProductId != null) {
            record(PairOutcome.FAILED);
        }
        if (failures.size() < DETAIL_LIMIT) {
            Map<String, Object> entry = pair(userDeviceId, candidateProductId);
            entry.put("stage", stage);
            entry.put("error", describe(error));
            failures.add(entry);
        }
    }

    /** The run had nothing to do; logged rather than treated as an error. */
    void skip(String reason) {
        skippedReason = reason;
    }

    public int count(PairOutcome outcome) {
        return outcomes.get(outcome);
    }

    public int failureCount() {
        return failureCount;
    }

    public Status status() {
        if (failureCount == 0) {
            return skippedReason != null ? Status.SKIPPED : Status.SUCCESS;
        }
        return pairsSucceeded() > 0 ? Status.PARTIAL_SUCCESS : Status.FAILURE;
    }

    int pairsSucceeded() {
        return outcomes.entrySet().stream()
                .filter(e -> e.getKey() != PairOutcome.FAILED)
                .mapToInt(Map.Entry::getValue)
                .sum();
    }

    String component() {
        return kind.component;
    }

    String message() {
        String subject = context.containsKey("market_event_id")
                ? "Market event " + context.get("market_event_id")
                : "Device " + context.get("user_device_id");
        if (skippedReason != null && failureCount == 0) {
            return subject + ": skipped, " + skippedReason;
        }
        return String.format(Locale.ROOT,
                "%s: %d pairs, %d assessed, %d rejected early, %d AI skipped, %d not shortlisted, %d failed",
                subject, pairsSelected,
                count(PairOutcome.ASSESSED), count(PairOutcome.REJECTED_EARLY), count(PairOutcome.AI_SKIPPED),
                count(PairOutcome.NOT_SHORTLISTED), failureCount);
    }

    Map<String, Object> metadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("trigger", kind.name());
        metadata.put("cause", cause);
        metadata.putAll(context);
        if (skippedReason != null) {
            metadata.put("skipped_reason", skippedReason);
        }
        metadata.put("pairs_selected", pairsSelected);
        metadata.put("pairs_succeeded", pairsSucceeded());
        metadata.put("pairs_assessed", count(PairOutcome.ASSESSED));
        metadata.put("pairs_rejected_early", count(PairOutcome.REJECTED_EARLY));
        metadata.put("pairs_ai_skipped", count(PairOutcome.AI_SKIPPED));
        metadata.put("pairs_not_shortlisted", count(PairOutcome.NOT_SHORTLISTED));
        metadata.put("pairs_unclassifiable", count(PairOutcome.UNCLASSIFIABLE));
        metadata.put("pairs_failed", count(PairOutcome.FAILED));
        metadata.put("rows_deleted", rowsDeleted);
        metadata.put("failures", failures);
        metadata.put("ai_skipped", aiSkips);
        metadata.put("duration_ms", (System.nanoTime() - startedNanos) / 1_000_000);
        return metadata;
    }

    private static Map<String, Object> pair(Long userDeviceId, Long candidateProductId) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("user_device_id", userDeviceId);
        entry.put("candidate_product_id", candidateProductId);
        return entry;
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        String text = error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return text.length() > 500 ? text.substring(0, 500) : text;
    }
}
