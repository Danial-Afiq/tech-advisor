package com.springboot.backend.recommendation;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wire compatibility with ai/app/schemas.py. Every model on that side is
 * declared extra="forbid", so a field name this side gets wrong is a 422 from
 * a live service rather than a quietly ignored key - these assertions are the
 * cheap way to catch that drift.
 */
@SuppressWarnings("unchecked")
class AssessContractTests {
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    private AssessRequest request() {
        return new AssessRequest(
                "req-1",
                new AssessRequest.UserContext(
                        new AssessRequest.OwnedDevice("Galaxy S22", 30, "FAIR", 45, List.of("photography")),
                        new AssessRequest.Preferences(
                                1200.0, "SGD", "URGENT", "FLEXIBLE", Map.of("battery", 5), "Dies by noon", null)),
                new AssessRequest.Candidate(812, "Galaxy S25", LocalDate.of(2025, 2, 7), 588),
                new AssessRequest.Computed(
                        Map.of("battery_mah", new AssessRequest.SpecDelta(3700.0, 4900.0, 32.4)),
                        61.2,
                        new AssessRequest.Price(1099.0, "SGD", -101.0, -8.3),
                        null),
                new AssessRequest.Analysis("WORTH_CONSIDERING", 0.71, 0.88, List.of("battery")),
                new AssessRequest.RetrievalOptions(12));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return json.convertValue(value, Map.class);
    }

    @Test
    void requestSerializesToTheSnakeCaseNamesPydanticDeclares() {
        Map<String, Object> body = asMap(request());
        assertEquals(
                java.util.Set.of("request_id", "user_context", "candidate", "computed", "analysis", "retrieval"),
                body.keySet());

        Map<String, Object> userContext = (Map<String, Object>) body.get("user_context");
        assertEquals(java.util.Set.of("owned_device", "preferences"), userContext.keySet());

        Map<String, Object> device = (Map<String, Object>) userContext.get("owned_device");
        assertEquals(
                java.util.Set.of("name", "device_age_months", "condition", "satisfaction_score", "use_cases"),
                device.keySet());

        Map<String, Object> preferences = (Map<String, Object>) userContext.get("preferences");
        assertEquals(
                java.util.Set.of(
                        "budget", "currency", "upgrade_urgency", "brand_flexibility", "priorities", "pain_points",
                        "notes"),
                preferences.keySet());

        Map<String, Object> candidate = (Map<String, Object>) body.get("candidate");
        assertEquals(java.util.Set.of("product_id", "name", "release_date", "age_days"), candidate.keySet());
        assertEquals("2025-02-07", candidate.get("release_date"), "pydantic expects a plain date, not a datetime");

        Map<String, Object> computed = (Map<String, Object>) body.get("computed");
        assertEquals(
                java.util.Set.of("spec_deltas", "benchmark_uplift_pct", "price", "trigger_event"), computed.keySet());

        Map<String, Object> analysis = (Map<String, Object>) body.get("analysis");
        assertEquals(
                java.util.Set.of("verdict", "relevance_score", "preference_score", "deciding_factors"),
                analysis.keySet());
    }

    @Test
    void omittedCollectionsBecomeEmptyRatherThanNull() {
        // Python types these as `list`/`dict` with a default, not Optional, so
        // an explicit JSON null is a 422 rather than "use the default".
        var device = new AssessRequest.OwnedDevice("Phone", 12, "GOOD", 60, null);
        var preferences = new AssessRequest.Preferences(null, null, "NOT_URGENT", "FLEXIBLE", null, null, null);
        var analysis = new AssessRequest.Analysis("WORTH_WATCHING", 0.1, 0.2, null);
        var request = new AssessRequest(
                "req-2",
                new AssessRequest.UserContext(device, preferences),
                new AssessRequest.Candidate(1, "P", LocalDate.of(2025, 1, 1), 1),
                null,
                analysis,
                null);

        Map<String, Object> body = asMap(request);
        Map<String, Object> userContext = asMap(body.get("user_context"));
        assertEquals(List.of(), asMap(userContext.get("owned_device")).get("use_cases"));
        assertEquals(Map.of(), asMap(userContext.get("preferences")).get("priorities"));
        assertNotNull(body.get("computed"), "computed must never serialize as null");
        assertNotNull(body.get("retrieval"), "retrieval must never serialize as null");
        assertEquals(Map.of(), asMap(body.get("computed")).get("spec_deltas"));
        assertEquals(List.of(), asMap(body.get("analysis")).get("deciding_factors"));
    }

    @Test
    void specDeltaCarriesEitherNumbersOrStrings() {
        // Python declares `float | str | None` for these.
        assertEquals(3700.0, asMap(new AssessRequest.SpecDelta(3700.0, 4900.0, 32.4)).get("current"));
        assertEquals("Snapdragon 8 Gen 1", asMap(new AssessRequest.SpecDelta("Snapdragon 8 Gen 1", "Elite", null))
                .get("current"));
    }

    @Test
    void responseDeserializesFromTheServicesSuccessBody() {
        String body =
                """
                {"request_id":"req-1","evidence_grade":"C",
                 "evidence_findings":[{"factor":"battery","stance":"NEGATIVE",
                   "supporting_refs":["P1"],"supporting_chunk_ids":[4412],"note":"Reduced endurance"}],
                 "irrelevant_refs":["P2"],"irrelevant_chunk_ids":[4430],
                 "summary":"Plain-language explanation.",
                 "meta":{"ai_model":"claude-opus-5","prompt_version":"v1",
                   "retrieved_chunk_ids":[4412,4430],"retry_count":0,
                   "retrieval":{"k":12,"chunk_char_cap":800,"vector_store":"local","embedding_dim":512},
                   "degraded":false,"degraded_reason":null},
                 "system_log":null}
                """;
        AssessResponse response = json.readValue(body, AssessResponse.class);

        assertEquals("C", response.evidenceGrade());
        assertEquals("Plain-language explanation.", response.summary());
        assertFalse(response.meta().degraded());
        assertNull(response.systemLog());
        assertEquals(List.of(4412L), response.evidenceFindings().getFirst().supportingChunkIds());
        assertEquals(List.of(4430L), response.irrelevantChunkIds());
        assertEquals("claude-opus-5", response.meta().aiModel());
        assertEquals(12, response.meta().retrieval().get("k"));
    }

    @Test
    void responseDeserializesFromADegradedBody() {
        String body =
                """
                {"request_id":"req-1","evidence_grade":"-","evidence_findings":[],
                 "irrelevant_refs":[],"irrelevant_chunk_ids":[],"summary":null,
                 "meta":{"ai_model":"claude-opus-5","prompt_version":"v1","retrieved_chunk_ids":[],
                   "retry_count":0,"retrieval":{"k":12},"degraded":true,
                   "degraded_reason":"NO_PASSAGES_RETRIEVED"},
                 "system_log":{"component":"recommendation_ai","status":"FAILURE",
                   "message":"No review passages retrieved for the candidate product",
                   "metadata":{"request_id":"req-1","reason":"NO_PASSAGES_RETRIEVED",
                     "retry_count":0,"candidate_product_id":812}}}
                """;
        AssessResponse response = json.readValue(body, AssessResponse.class);

        assertEquals("-", response.evidenceGrade());
        assertNull(response.summary());
        assertTrue(response.meta().degraded());
        assertEquals("NO_PASSAGES_RETRIEVED", response.meta().degradedReason());
        assertEquals("recommendation_ai", response.systemLog().get("component"));
    }
}
