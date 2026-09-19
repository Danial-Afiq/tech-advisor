package com.springboot.backend.recommendation;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end persistence: Spring serializes an AssessRequest, POSTs it over
 * real HTTP, deserializes the response and writes the recommendation.
 *
 * The AI service is stood in for by a local HttpServer rather than mocked at
 * the client boundary, so the JSON actually crossing the wire is exercised -
 * that contract has never been run before (AGENTS.md §18.7). No LLM is
 * involved and no API credit is spent.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(properties = {"ingestion.reconciliation-enabled=false", "logging.level.root=WARN", "debug=false"})
class RecommendationPersistenceTests {
    static HttpServer server;
    static final AtomicReference<String> RESPONSE = new AtomicReference<>();
    static final AtomicReference<String> LAST_REQUEST_BODY = new AtomicReference<>();
    static final AtomicReference<String> LAST_AUTHORIZATION = new AtomicReference<>();
    static final AtomicReference<String> LAST_CONTENT_TYPE = new AtomicReference<>();
    static final AtomicReference<Integer> STATUS = new AtomicReference<>(200);

    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    @Autowired RecommendationService service;
    @Autowired JdbcTemplate db;

    long userId;
    long productId;

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/assess", exchange -> {
            LAST_REQUEST_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            LAST_AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
            LAST_CONTENT_TYPE.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] body = RESPONSE.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(STATUS.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterAll
    static void stopStub() {
        server.stop(0);
    }

    @DynamicPropertySource
    static void aiService(DynamicPropertyRegistry registry) {
        registry.add("ai.service-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
        registry.add("ai.service-token", () -> "test-service-token");
    }

    @BeforeEach
    void reset() {
        assertTrue(
                db.queryForObject("SELECT current_database()", String.class).endsWith("_test"),
                "Integration tests require a dedicated database whose name ends in _test");
        db.update("DELETE FROM recommendations");
        db.update("DELETE FROM system_log WHERE component = 'recommendation_ai'");
        db.update("DELETE FROM users WHERE email LIKE 'recommendation-test%'");
        db.update("DELETE FROM products WHERE brand = 'TestBrand'");

        // password_hash is NOT NULL from V5, which the authentication feature owns.
        // These tests never authenticate as this user - the fixture only needs a row
        // to hang recommendations off - so the column gets a placeholder that is
        // deliberately not a valid encoded hash and can never verify against input.
        userId = db.queryForObject(
                "INSERT INTO users (email, role, password_hash) "
                        + "VALUES ('recommendation-test@example.com','USER','{noop}not-a-real-hash') "
                        + "RETURNING id",
                Long.class);
        productId = db.queryForObject(
                "INSERT INTO products (brand, model_name, release_date) "
                        + "VALUES ('TestBrand','Model X','2025-02-07') RETURNING id",
                Long.class);
        STATUS.set(200);
    }

    private RecommendationInput input() {
        var request = new AssessRequest(
                null,
                new AssessRequest.UserContext(
                        new AssessRequest.OwnedDevice("Old Phone", 30, "FAIR", 45, List.of("photography")),
                        new AssessRequest.Preferences(
                                1200.0, "SGD", "URGENT", "FLEXIBLE", Map.of("battery", 5), "Dies by noon", null)),
                new AssessRequest.Candidate(productId, "Model X", LocalDate.of(2025, 2, 7), 588),
                new AssessRequest.Computed(
                        Map.of("battery_mah", new AssessRequest.SpecDelta(3700.0, 4900.0, 32.4)), 61.2, null, null),
                new AssessRequest.Analysis("WORTH_CONSIDERING", 0.71, 0.88, List.of("battery")),
                new AssessRequest.RetrievalOptions(12));
        return new RecommendationInput(
                userId, null, null, request, Map.of("battery", Map.of("priority", 5, "impact", "HIGH_POSITIVE")));
    }

    private String successBody() {
        return """
                {"request_id":"generated","evidence_grade":"C",
                 "evidence_findings":[{"factor":"battery","stance":"NEGATIVE",
                   "supporting_refs":["P1"],"supporting_chunk_ids":[4412],"note":"Reduced endurance"}],
                 "irrelevant_refs":["P2"],"irrelevant_chunk_ids":[4430],
                 "summary":"Owners report weaker battery than the specs suggest.",
                 "meta":{"ai_model":"claude-opus-5","prompt_version":"v1",
                   "retrieved_chunk_ids":[4412,4430],"retry_count":0,
                   "retrieval":{"k":12,"chunk_char_cap":800,"vector_store":"local","embedding_dim":512},
                   "degraded":false,"degraded_reason":null},
                 "system_log":null}
                """;
    }

    private String degradedBody() {
        return """
                {"request_id":"generated","evidence_grade":"-","evidence_findings":[],
                 "irrelevant_refs":[],"irrelevant_chunk_ids":[],"summary":null,
                 "meta":{"ai_model":"claude-opus-5","prompt_version":"v1","retrieved_chunk_ids":[],
                   "retry_count":0,"retrieval":{"k":12},"degraded":true,
                   "degraded_reason":"NO_PASSAGES_RETRIEVED"},
                 "system_log":{"component":"recommendation_ai","status":"FAILURE",
                   "message":"No review passages retrieved for the candidate product",
                   "metadata":{"reason":"NO_PASSAGES_RETRIEVED","retry_count":0,
                     "candidate_product_id":812}}}
                """;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonColumn(long id, String column) {
        return json.readValue(
                db.queryForObject("SELECT " + column + "::text FROM recommendations WHERE id=?", String.class, id),
                Map.class);
    }

    @Test
    void successfulAssessmentIsPersistedForTheUserAndProduct() {
        RESPONSE.set(successBody());
        long id = service.assessAndPersist(input());

        var row = db.queryForMap("SELECT * FROM recommendations WHERE id=?", id);
        assertEquals(userId, ((Number) row.get("user_id")).longValue());
        assertEquals(productId, ((Number) row.get("candidate_product_id")).longValue());
        assertEquals("WORTH_CONSIDERING", row.get("verdict"), "the verdict stays Java-owned, not the model's");
        assertEquals("C", row.get("confidence"), "confidence holds the A-F evidence grade");
        assertEquals("Owners report weaker battery than the specs suggest.", row.get("reasoning"));
        assertEquals("claude-opus-5", row.get("ai_model"));
        assertEquals("v1", row.get("prompt_version"));
        assertEquals("ACTIVE", row.get("status"));
        assertNull(row.get("current_device_id"), "no user_devices table exists yet to point at");

        Map<String, Object> factors = jsonColumn(id, "factor_analysis");
        assertEquals(
                Map.of("battery", Map.of("priority", 5, "impact", "HIGH_POSITIVE")), factors.get("deterministic"));
        var evidence = (List<Map<String, Object>>) factors.get("evidence");
        assertEquals(List.of(4412), evidence.getFirst().get("supporting_chunk_ids"));
        assertFalse(
                evidence.getFirst().containsKey("supporting_refs"),
                "P* refs are per-request labels and must never be persisted");
        assertEquals(List.of(4430), factors.get("irrelevant_chunk_ids"));

        Map<String, Object> snapshot = jsonColumn(id, "input_snapshot");
        assertEquals(List.of(4412, 4430), snapshot.get("retrieved_chunk_ids"));
        assertEquals(12, ((Map<String, Object>) snapshot.get("retrieval")).get("k"));
        assertNotNull(snapshot.get("owned_device"));
        assertNotNull(snapshot.get("preferences"));
        assertNotNull(snapshot.get("computed"));

        assertEquals(
                0,
                (int) db.queryForObject(
                        "SELECT count(*) FROM system_log WHERE component='recommendation_ai'", Integer.class),
                "a successful assessment logs no failure");
    }

    @Test
    void degradedAssessmentStillPersistsTheVerdictAndRecordsTheFailure() {
        RESPONSE.set(degradedBody());
        long id = service.assessAndPersist(input());

        var row = db.queryForMap("SELECT * FROM recommendations WHERE id=?", id);
        assertEquals("-", row.get("confidence"), "'-' means we cannot yet say, never a low grade");
        assertNull(row.get("reasoning"), "no summary may be fabricated on a degraded path");
        assertEquals("WORTH_CONSIDERING", row.get("verdict"), "the deterministic half survives AI failure");

        Map<String, Object> factors = jsonColumn(id, "factor_analysis");
        assertEquals(List.of(), factors.get("evidence"));
        assertEquals(
                Map.of("battery", Map.of("priority", 5, "impact", "HIGH_POSITIVE")),
                factors.get("deterministic"),
                "caller-supplied deterministic analysis is not lost when the AI degrades");

        var log = db.queryForMap("SELECT * FROM system_log WHERE component='recommendation_ai'");
        assertEquals("FAILURE", log.get("status"));
        assertEquals("No review passages retrieved for the candidate product", log.get("message"));
    }

    @Test
    void reassessmentSupersedesThePreviousActiveRow() {
        RESPONSE.set(successBody());
        long first = service.assessAndPersist(input());
        long second = service.assessAndPersist(input());

        assertNotEquals(first, second);
        assertEquals(
                "SUPERSEDED",
                db.queryForObject("SELECT status FROM recommendations WHERE id=?", String.class, first));
        assertEquals(
                1,
                (int) db.queryForObject(
                        "SELECT count(*) FROM recommendations WHERE user_id=? AND candidate_product_id=? "
                                + "AND status='ACTIVE'",
                        Integer.class,
                        userId,
                        productId));
    }

    @Test
    void theRequestCrossesTheWireInThePydanticShapeWithTheBearerToken() {
        RESPONSE.set(successBody());
        service.assessAndPersist(input());

        assertEquals("Bearer test-service-token", LAST_AUTHORIZATION.get());
        // FastAPI binds the body only when this is set; without it the real
        // service answers 422 "Field required, loc: body".
        assertTrue(
                LAST_CONTENT_TYPE.get() != null && LAST_CONTENT_TYPE.get().startsWith("application/json"),
                "Content-Type was " + LAST_CONTENT_TYPE.get());
        String body = LAST_REQUEST_BODY.get();
        assertTrue(body.contains("\"request_id\""), body);
        assertTrue(body.contains("\"device_age_months\":30"), body);
        assertTrue(body.contains("\"release_date\":\"2025-02-07\""), body);
        assertTrue(body.contains("\"benchmark_uplift_pct\":61.2"), body);
        assertTrue(body.contains("\"relevance_score\":0.71"), body);
        assertFalse(body.contains("requestId"), "camelCase would be rejected by pydantic's extra=forbid");
    }

    @Test
    void anUnreachableAiServicePersistsNothing() {
        STATUS.set(500);
        RESPONSE.set("{\"detail\":\"boom\"}");

        assertThrows(AiServiceException.class, () -> service.assessAndPersist(input()));
        assertEquals(
                0, (int) db.queryForObject("SELECT count(*) FROM recommendations", Integer.class),
                "a transport failure is not a degraded assessment and must not write a half-row");
    }
}
