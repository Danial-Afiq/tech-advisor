package com.springboot.backend.recommendation;

import com.springboot.backend.recommendation.classification.Factors;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Rejects an invalid assessment before any recommendation is superseded or saved. */
final class AssessmentValidation {
    private static final List<String> DEGRADED_REASONS = List.of(
            "RETRIEVAL_FAILED", "NO_PASSAGES_RETRIEVED", "INSUFFICIENT_RELEVANT_PASSAGES",
            "LLM_CALL_FAILED", "VALIDATION_FAILED");

    private AssessmentValidation() {}

    static void validateAnalysis(AssessRequest.Analysis analysis) {
        require(analysis != null, "analysis");
        require(analysis.verdict() != null && AssessVocabulary.VERDICTS.contains(analysis.verdict()), "verdict");
        require(Double.isFinite(analysis.upgradeScore())
                && analysis.upgradeScore() >= 0 && analysis.upgradeScore() <= 1, "upgrade_score");
    }

    static void validateResponse(AssessResponse response) {
        require(response != null, "response");
        require(hasText(response.requestId()), "request_id");
        var meta = response.meta();
        require(meta != null, "meta");
        require(hasText(meta.aiModel()), "meta.ai_model");
        require(hasText(meta.promptVersion()), "meta.prompt_version");
        require(meta.retryCount() >= 0 && meta.retryCount() <= 1, "meta.retry_count");
        require(validIds(meta.retrievedChunkIds()), "meta.retrieved_chunk_ids");
        validateRetrieval(meta.retrieval());

        if (meta.degraded()) {
            require(meta.degradedReason() != null && DEGRADED_REASONS.contains(meta.degradedReason()),
                    "meta.degraded_reason");
            require("-".equals(response.evidenceGrade()) && response.summary() == null
                    && response.evidenceFindings().isEmpty() && response.irrelevantRefs().isEmpty()
                    && response.irrelevantChunkIds().isEmpty(), "degraded assessment");
            var failure = response.systemLog();
            require(failure != null && "recommendation_ai".equals(failure.get("component"))
                    && "FAILURE".equals(failure.get("status"))
                    && failure.get("message") instanceof String message && hasText(message)
                    && failure.get("metadata") instanceof Map<?, ?>, "system_log");
            return;
        }

        require(meta.degradedReason() == null && response.systemLog() == null, "success metadata");
        require(response.evidenceGrade() != null && AssessVocabulary.GRADES.contains(response.evidenceGrade()),
                "evidence_grade");
        require(hasText(response.summary()), "summary");
        require(!response.evidenceFindings().isEmpty(), "evidence_findings");
        require(validIds(response.irrelevantChunkIds())
                && meta.retrievedChunkIds().containsAll(response.irrelevantChunkIds())
                && response.irrelevantRefs().size() == response.irrelevantChunkIds().size(), "irrelevant_chunk_ids");
        for (var finding : response.evidenceFindings()) {
            require(finding.factor() != null && Factors.ALL.contains(finding.factor()), "factor");
            require(finding.stance() != null && AssessVocabulary.STANCES.contains(finding.stance()), "stance");
            require(hasText(finding.note()), "note");
            require(!finding.supportingChunkIds().isEmpty() && validIds(finding.supportingChunkIds())
                    && meta.retrievedChunkIds().containsAll(finding.supportingChunkIds())
                    && finding.supportingRefs().size() == finding.supportingChunkIds().size()
                    && finding.supportingChunkIds().stream().noneMatch(response.irrelevantChunkIds()::contains),
                    "supporting_chunk_ids");
        }
    }

    private static void validateRetrieval(Map<String, Object> retrieval) {
        for (String field : List.of("k", "chunk_char_cap", "embedding_dim")) {
            Object value = retrieval.get(field);
            require((value instanceof Integer || value instanceof Long)
                    && ((Number) value).longValue() > 0, "meta.retrieval." + field);
        }
        require("local".equals(retrieval.get("vector_store"))
                || "pgvector".equals(retrieval.get("vector_store")), "meta.retrieval.vector_store");
    }

    private static boolean validIds(List<Long> ids) {
        return ids.stream().allMatch(id -> id != null && id > 0)
                && new HashSet<>(ids).size() == ids.size();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String field) {
        if (!condition) throw new AiServiceException("Invalid AI assessment field: " + field, null);
    }
}
