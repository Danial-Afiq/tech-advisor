package com.springboot.backend.recommendation;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Wire-exact mirror of {@code ai/app/schemas.py::AssessResponse}. Deserialized
 * from the AI service's {@code POST /assess} response. Because that side sets
 * {@code response_model=AssessResponse}, every field is always present in the
 * JSON (defaults included) - never fields we omitted here.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AssessResponse(
        String requestId,
        String evidenceGrade,
        List<EvidenceFinding> evidenceFindings,
        List<String> irrelevantRefs,
        List<Long> irrelevantChunkIds,
        String summary,
        ResponseMeta meta,
        Map<String, Object> systemLog) {

    public AssessResponse {
        evidenceFindings = evidenceFindings == null ? List.of() : List.copyOf(evidenceFindings);
        irrelevantRefs = irrelevantRefs == null ? List.of() : List.copyOf(irrelevantRefs);
        irrelevantChunkIds = irrelevantChunkIds == null ? List.of() : List.copyOf(irrelevantChunkIds);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EvidenceFinding(
            String factor, String stance, List<String> supportingRefs, List<Long> supportingChunkIds, String note) {
        public EvidenceFinding {
            supportingRefs = supportingRefs == null ? List.of() : List.copyOf(supportingRefs);
            supportingChunkIds = supportingChunkIds == null ? List.of() : List.copyOf(supportingChunkIds);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ResponseMeta(
            String aiModel,
            String promptVersion,
            List<Long> retrievedChunkIds,
            int retryCount,
            Map<String, Object> retrieval,
            boolean degraded,
            String degradedReason) {
        public ResponseMeta {
            retrievedChunkIds = retrievedChunkIds == null ? List.of() : List.copyOf(retrievedChunkIds);
            retrieval = retrieval == null ? Map.of() : Map.copyOf(retrieval);
        }
    }
}
