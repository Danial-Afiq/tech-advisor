package com.springboot.backend.recommendation;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Wire-exact mirror of {@code ai/app/schemas.py::AssessRequest}. Every nested
 * record here has a Python counterpart with the same fields, in the same
 * nesting, under {@code extra="forbid"} - so this side must never serialize
 * a field the Python model does not declare, and field names must convert to
 * the same snake_case wire names.
 *
 * Sent to the AI service exactly as-is; Java owns every number in it
 * (AGENTS.md §8.2/§9), so nothing here is recomputed on the Python side.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AssessRequest(
        String requestId,
        UserContext userContext,
        Candidate candidate,
        Computed computed,
        Analysis analysis,
        RetrievalOptions retrieval) {

    public AssessRequest {
        computed = computed == null ? Computed.empty() : computed;
        retrieval = retrieval == null ? RetrievalOptions.none() : retrieval;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UserContext(OwnedDevice ownedDevice, Preferences preferences) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OwnedDevice(
            String name,
            int deviceAgeMonths,
            String condition,
            int satisfactionScore,
            List<String> useCases) {
        public OwnedDevice {
            useCases = useCases == null ? List.of() : List.copyOf(useCases);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Preferences(
            Double budget,
            String currency,
            String upgradeUrgency,
            String brandFlexibility,
            Map<String, Integer> priorities,
            String painPoints,
            String notes) {
        public Preferences {
            priorities = priorities == null ? Map.of() : Map.copyOf(priorities);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Candidate(long productId, String name, LocalDate releaseDate, int ageDays) {}

    /**
     * {@code current}/{@code candidate} are {@code float | str | None} on the
     * Python side. Java has no union type; callers pass whichever boxed type
     * (Double or String) applies and Jackson serializes it as-is.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SpecDelta(Object current, Object candidate, Double deltaPct) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Price(Double current, String currency, Double vsBudget, Double changePct) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TriggerEvent(
            String eventType, String title, Map<String, Object> oldValue, Map<String, Object> newValue) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Computed(
            Map<String, SpecDelta> specDeltas, Double benchmarkUpliftPct, Price price, TriggerEvent triggerEvent) {
        public Computed {
            specDeltas = specDeltas == null ? Map.of() : Map.copyOf(specDeltas);
        }

        static Computed empty() {
            return new Computed(Map.of(), null, null, null);
        }
    }

    /**
     * Channel A's output (AGENTS.md §7.1). {@code upgradeScore} is the single
     * aggregate produced by the deterministic classifier on a 0-1 scale, where
     * 1.0 is a strong upgrade recommendation and 0.0 is not recommended; the
     * verdict is the tier that score maps to, never the model's opinion.
     *
     * <p>This replaced an earlier pair of 0-1 {@code relevance_score} /
     * {@code preference_score} fields. One score is what the tier is actually
     * derived from, and two numbers that never independently drove anything
     * invited callers to average or compare them.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Analysis(String verdict, double upgradeScore, List<String> decidingFactors) {
        public Analysis {
            decidingFactors = decidingFactors == null ? List.of() : List.copyOf(decidingFactors);
        }
    }

    public record RetrievalOptions(Integer k) {
        static RetrievalOptions none() {
            return new RetrievalOptions(null);
        }
    }
}
