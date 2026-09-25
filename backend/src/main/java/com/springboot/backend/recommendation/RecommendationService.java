package com.springboot.backend.recommendation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Orchestrates one assessment: call {@code POST /assess} with caller-supplied
 * context, then persist the result into {@code recommendations} - the entire
 * completion criterion for this ticket. Does not compute a verdict, does not
 * schedule itself; both are separate tickets (AGENTS.md §7.1, §27.9-adjacent
 * scheduler work).
 */
@Service
public class RecommendationService {
    private final AiAssessmentClient aiClient;
    private final RecommendationRepository repository;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    public RecommendationService(AiAssessmentClient aiClient, RecommendationRepository repository) {
        this.aiClient = aiClient;
        this.repository = repository;
    }

    public long assessAndPersist(RecommendationInput input) {
        AssessRequest request = withRequestId(input.request());
        AssessResponse response = aiClient.assess(request);

        Map<String, Object> degradedLog = null;
        if (response.meta().degraded()) {
            if (response.systemLog() == null) {
                // The AI service's own contract (AGENTS.md §10) is that a
                // degraded meta always carries a ready-formed system_log row.
                // If that's ever violated, the failure must not be silently
                // dropped - fail loudly instead of persisting a recommendation
                // with no record of why the grade is "-".
                throw new AiServiceException(
                        "Degraded assessment for request " + request.requestId()
                                + " carried no system_log entry",
                        null);
            }
            degradedLog = response.systemLog();
        }

        RecommendationRecord record = new RecommendationRecord(
                input.userId(),
                input.currentDeviceId(),
                request.candidate().productId(),
                input.triggerEventId(),
                request.analysis().verdict(),
                response.evidenceGrade(),
                buildInputSnapshot(request, response),
                buildFactorAnalysis(input, response),
                response.summary(),
                response.meta().aiModel(),
                response.meta().promptVersion());

        return repository.save(record, degradedLog);
    }

    private AssessRequest withRequestId(AssessRequest request) {
        if (request.requestId() != null && !request.requestId().isBlank()) return request;
        return new AssessRequest(
                UUID.randomUUID().toString(),
                request.userContext(),
                request.candidate(),
                request.computed(),
                request.analysis(),
                request.retrieval());
    }

    private Map<String, Object> buildInputSnapshot(AssessRequest request, AssessResponse response) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("owned_device", toMap(request.userContext().ownedDevice()));
        snapshot.put("preferences", toMap(request.userContext().preferences()));
        snapshot.put("candidate", toMap(request.candidate()));
        snapshot.put("computed", toMap(request.computed()));
        // Channel A's own output, kept so a persisted recommendation stays
        // explainable after the scoring configuration changes. Without the
        // score and the version that produced it, an old row's verdict cannot
        // be re-derived once the thresholds move (AGENTS.md §7.1, §27.5).
        snapshot.put("analysis", toMap(request.analysis()));
        // Two recommendations sharing a prompt_version must also share these
        // retrieval parameters or reproducibility is lost (AGENTS.md §10).
        snapshot.put("retrieval", response.meta().retrieval());
        snapshot.put("retrieved_chunk_ids", response.meta().retrievedChunkIds());
        return snapshot;
    }

    private Map<String, Object> buildFactorAnalysis(RecommendationInput input, AssessResponse response) {
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("deterministic", input.deterministicFactors());

        List<Map<String, Object>> evidence = new ArrayList<>();
        for (AssessResponse.EvidenceFinding finding : response.evidenceFindings()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("factor", finding.factor());
            entry.put("stance", finding.stance());
            // Never the ephemeral P* refs (finding.supportingRefs) - only the
            // real review_chunks.id values survive outside the request that
            // produced them (AGENTS.md §8.6).
            entry.put("supporting_chunk_ids", finding.supportingChunkIds());
            entry.put("note", finding.note());
            evidence.add(entry);
        }
        analysis.put("evidence", evidence);
        analysis.put("irrelevant_chunk_ids", response.irrelevantChunkIds());
        return analysis;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object value) {
        return json.convertValue(value, Map.class);
    }
}
