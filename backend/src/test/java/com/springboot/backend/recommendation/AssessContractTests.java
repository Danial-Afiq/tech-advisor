package com.springboot.backend.recommendation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wire compatibility with ai/app/schemas.py. Every model on that side is
 * declared extra="forbid", so a field name this side gets wrong is a 422 from
 * a live service rather than a quietly ignored key.
 *
 * Field names are checked against contracts/ai-service-schema.json, not a
 * hand-typed set - that file is generated from the real Pydantic models
 * (ai/scripts/export_schema.py) and ai/tests/test_schema_contract.py already
 * fails if it drifts from the real schema. Checking against the same file
 * here means a real shape change fails on both sides, not just a human
 * remembering to update this file's assertions by hand.
 */
class AssessContractTests {
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private final JsonNode schemas = loadSchemas();

    private static JsonNode loadSchemas() {
        // Repo-root contracts/, same ../ pattern application.properties already
        // uses for ../.env - Maven's test working directory is the module root.
        Path path = Path.of("../contracts/ai-service-schema.json");
        try {
            return JsonMapper.builder().build().readTree(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOThatIsntSupposedToHappenInTests(path, e);
        }
    }

    private static final class UncheckedIOThatIsntSupposedToHappenInTests extends RuntimeException {
        UncheckedIOThatIsntSupposedToHappenInTests(Path path, IOException cause) {
            super("Could not read " + path.toAbsolutePath() + " - run ai/scripts/export_schema.py first", cause);
        }
    }

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
                new AssessRequest.Analysis("WORTH_CONSIDERING", 0.72, List.of("battery")),
                new AssessRequest.RetrievalOptions(12));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return json.convertValue(value, Map.class);
    }

    private JsonNode resolveRef(JsonNode node) {
        if (node.has("$ref")) {
            String name = node.path("$ref").asText();
            return schemas.get(name.substring(name.lastIndexOf('/') + 1));
        }
        return node;
    }

    /**
     * Recursively asserts {@code value} (a Jackson-serialized Map/List/primitive) has exactly
     * the field names {@code schemaNode} declares, at every nesting level. Nullable fields
     * (Pydantic {@code anyOf: [X, null]}) are only checked when the actual value isn't null -
     * there is nothing more to verify about a field that's legitimately absent-as-null.
     */
    private void assertMatchesSchema(JsonNode schemaNode, Object value, String path) {
        JsonNode schema = resolveRef(schemaNode);
        if (schema.has("anyOf")) {
            if (value == null) return; // satisfies the null branch, nothing further to check
            for (JsonNode option : schema.get("anyOf")) {
                JsonNode resolved = resolveRef(option);
                boolean objectOption = "object".equals(resolved.path("type").asText(""));
                if (objectOption == (value instanceof Map)) {
                    assertMatchesSchema(option, value, path);
                    return;
                }
            }
            return; // a primitive anyOf branch matched by elimination - no structure left to check
        }
        if (!"object".equals(schema.path("type").asText(""))) return; // primitive leaf, nothing to check
        if (schema.has("properties")) {
            assertInstanceOf(Map.class, value, path + " must serialize as an object");
            @SuppressWarnings("unchecked") var map = (Map<String, Object>) value;
            var expected = new TreeSet<>(schema.get("properties").propertyNames());
            assertEquals(expected, new TreeSet<>(map.keySet()), "field names at " + path);
            for (String field : expected) {
                assertMatchesSchema(schema.get("properties").get(field), map.get(field), path + "." + field);
            }
        } else if (schema.has("additionalProperties") && schema.get("additionalProperties").isObject()) {
            // An open map keyed by something other than fixed field names (spec_deltas,
            // priorities) - no field-name check at this level, but still recurse into values.
            assertInstanceOf(Map.class, value, path + " must serialize as an object");
            @SuppressWarnings("unchecked") var map = (Map<String, Object>) value;
            map.forEach((key, v) -> assertMatchesSchema(schema.get("additionalProperties"), v, path + "[" + key + "]"));
        }
    }

    @Test
    void requestSerializesToTheFieldNamesTheRealContractDeclares() {
        assertMatchesSchema(schemas.get("AssessRequest"), asMap(request()), "AssessRequest");
    }

    @Test
    void responseSerializesToTheFieldNamesTheRealContractDeclares() {
        // Round-trips a realistic deserialize-then-reserialize, same as
        // responseDeserializesFromTheServicesSuccessBody's fixture - exercises field names in
        // both directions rather than only ever checking the request side, which the old
        // hand-typed version of this test never did for AssessResponse at all.
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
                 "system_log":{"component":"recommendation_ai"}}
                """;
        AssessResponse response = json.readValue(body, AssessResponse.class);

        assertMatchesSchema(schemas.get("AssessResponse"), asMap(response), "AssessResponse");
    }

    @Test
    void omittedCollectionsBecomeEmptyRatherThanNull() {
        // Python types these as `list`/`dict` with a default, not Optional, so
        // an explicit JSON null is a 422 rather than "use the default".
        var device = new AssessRequest.OwnedDevice("Phone", 12, "GOOD", 60, null);
        var preferences = new AssessRequest.Preferences(null, null, "NOT_URGENT", "FLEXIBLE", null, null, null);
        var analysis = new AssessRequest.Analysis("WORTH_WATCHING", 0.58, null);
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
    void unknownDatesSerializeAsExplicitNullContext() {
        var request = new AssessRequest(
                "req-dates",
                new AssessRequest.UserContext(
                        new AssessRequest.OwnedDevice("Phone", null, "GOOD", 60, List.of()),
                        new AssessRequest.Preferences(
                                900.0, "SGD", "NOT_URGENT", "FLEXIBLE", Map.of(), null, null)),
                new AssessRequest.Candidate(1, "Candidate", null, null),
                null,
                new AssessRequest.Analysis("WORTH_WATCHING", 0.58, List.of()),
                null);

        Map<String, Object> body = asMap(request);
        Map<String, Object> context = asMap(body.get("user_context"));
        assertNull(asMap(context.get("owned_device")).get("device_age_months"));
        assertNull(asMap(body.get("candidate")).get("release_date"));
        assertNull(asMap(body.get("candidate")).get("age_days"));
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
