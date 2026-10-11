package com.springboot.backend.recommendation;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** No database or external AI service: invalid responses must never reach save. */
class RecommendationValidationTest {
    private static final String SUCCESS = """
            {"request_id":"req-1","evidence_grade":"B",
             "evidence_findings":[{"factor":"battery","stance":"POSITIVE",
               "supporting_refs":["P1"],"supporting_chunk_ids":[4412],"note":"Good endurance"}],
             "irrelevant_refs":[],"irrelevant_chunk_ids":[],"summary":"Owners report good endurance.",
             "meta":{"ai_model":"fake-model","prompt_version":"v1","retrieved_chunk_ids":[4412],
               "retry_count":0,"retrieval":{"k":12,"chunk_char_cap":800,"vector_store":"local",
               "embedding_dim":512},"degraded":false,"degraded_reason":null},"system_log":null}
            """;

    private final AiAssessmentClient ai = mock(AiAssessmentClient.class);
    private final RecommendationRepository repository = mock(RecommendationRepository.class);
    private final RecommendationService service = new RecommendationService(ai, repository);
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    private RecommendationInput input(String verdict, double score) {
        var request = new AssessRequest(
                "req-1",
                new AssessRequest.UserContext(
                        new AssessRequest.OwnedDevice("Old phone", 30, "FAIR", 45, List.of()),
                        new AssessRequest.Preferences(1200.0, "SGD", "URGENT", "FLEXIBLE", Map.of(), null, null)),
                new AssessRequest.Candidate(812, "Phone", LocalDate.of(2025, 2, 7), 588),
                null, new AssessRequest.Analysis(verdict, score, List.of("battery")), null);
        return new RecommendationInput(1, null, null, request, Map.of("battery", Map.of("impact", "HIGH_POSITIVE")));
    }

    @Test
    void aValidatedAssessmentReachesPersistence() {
        when(ai.assess(any())).thenReturn(json.readValue(SUCCESS, AssessResponse.class));
        when(repository.save(any(), any())).thenReturn(42L);

        assertEquals(42L, service.assessAndPersist(input("WORTH_CONSIDERING", 0.72)));
        verify(repository).save(argThat(record ->
                "WORTH_CONSIDERING".equals(record.verdict()) && "B".equals(record.confidence())
                        && "Owners report good endurance.".equals(record.reasoning())), isNull());
    }

    static Stream<String> invalidResponses() {
        return Stream.of(
                SUCCESS.replace("\"evidence_grade\":\"B\"", "\"evidence_grade\":\"Z\""),
                SUCCESS.replace("\"evidence_grade\":\"B\"", "\"evidence_grade\":\"-\""),
                SUCCESS.replace("\"evidence_grade\":\"B\"", "\"evidence_grade\":null"),
                SUCCESS.replace("\"summary\":\"Owners report good endurance.\"", "\"summary\":null"),
                SUCCESS.replace("Owners report good endurance.", "   "),
                SUCCESS.replace("\"meta\":{", "\"meta\":null,\"discarded_meta\":{"),
                SUCCESS.replace("\"ai_model\":\"fake-model\"", "\"ai_model\":\"\""),
                SUCCESS.replace("\"prompt_version\":\"v1\"", "\"prompt_version\":\"\""),
                SUCCESS.replace("\"retry_count\":0", "\"retry_count\":2"),
                SUCCESS.replace("\"retry_count\":0", "\"retry_count\":-1"),
                SUCCESS.replace("\"k\":12", "\"k\":\"12\""),
                SUCCESS.replace("\"vector_store\":\"local\"", "\"vector_store\":\"unknown\""),
                SUCCESS.replace("\"factor\":\"battery\"", "\"factor\":\"vibes\""),
                SUCCESS.replace("\"stance\":\"POSITIVE\"", "\"stance\":\"GREAT\""),
                SUCCESS.replace("Good endurance", ""),
                SUCCESS.replace("\"supporting_chunk_ids\":[4412]", "\"supporting_chunk_ids\":[]"),
                SUCCESS.replace("\"supporting_chunk_ids\":[4412]", "\"supporting_chunk_ids\":[9999]"),
                SUCCESS.replace("\"degraded\":false", "\"degraded\":true"));
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void invalidAssessmentNeverReachesPersistence(String body) {
        when(ai.assess(any())).thenReturn(json.readValue(body, AssessResponse.class));

        assertThrows(AiServiceException.class, () -> service.assessAndPersist(input("WORTH_CONSIDERING", 0.72)));
        verifyNoInteractions(repository);
    }

    @Test
    void anEmptyServiceResponseNeverReachesPersistence() {
        assertThrows(AiServiceException.class, () -> service.assessAndPersist(input("WORTH_CONSIDERING", 0.72)));
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BUY_NOW", "worth_watching", ""})
    void aNoncanonicalVerdictIsRejectedBeforeAnAiCall(String verdict) {
        assertThrows(AiServiceException.class, () -> service.assessAndPersist(input(verdict, 0.72)));
        verifyNoInteractions(ai, repository);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY})
    void anInvalidScoreIsRejectedBeforeAnAiCall(double score) {
        assertThrows(AiServiceException.class, () -> service.assessAndPersist(input("WORTH_CONSIDERING", score)));
        verifyNoInteractions(ai, repository);
    }

    @Test
    void aValidationFailurePersistsOnlyTheUnavailableAssessmentAndFailureLog() {
        Map<String, Object> failure = Map.of(
                "component", "recommendation_ai", "status", "FAILURE",
                "message", "Schema validation failed after retry", "metadata", Map.of("retry_count", 1));
        var meta = new AssessResponse.ResponseMeta("fake-model", "v1", List.of(4412L), 1,
                Map.of("k", 12, "chunk_char_cap", 800, "vector_store", "local", "embedding_dim", 512),
                true, "VALIDATION_FAILED");
        when(ai.assess(any())).thenReturn(new AssessResponse(
                "req-1", "-", List.of(), List.of(), List.of(), null, meta, failure));
        service.assessAndPersist(input("WORTH_CONSIDERING", 0.72));

        verify(repository).save(argThat(record ->
                "-".equals(record.confidence()) && record.reasoning() == null
                        && "WORTH_CONSIDERING".equals(record.verdict())
                        && List.of().equals(record.factorAnalysis().get("evidence"))), eq(failure));
    }
}
