package com.springboot.backend.recommendation;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real socket, not a mock - same pattern as EmbeddingClientTests/FrankfurterClientTests.
 * AiAssessmentClient is the one call that drives the actual LLM-backed evidence grade;
 * elsewhere it only ever appears as a mocked bean, so nothing exercised its own
 * request-building/response-parsing logic until now.
 */
class AiAssessmentClientTests {
    private final JsonMapper json = JsonMapper.builder().build();

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

    private String assessResponseBody(boolean degraded) {
        return """
                {"request_id":"req-1","evidence_grade":"B",
                 "evidence_findings":[{"factor":"battery","stance":"SUPPORTS",
                   "supporting_refs":["r1"],"supporting_chunk_ids":[101],"note":"Reviewers report longer battery life"}],
                 "irrelevant_refs":[],"irrelevant_chunk_ids":[],
                 "summary":"Battery life improves meaningfully; worth considering.",
                 "meta":{"ai_model":"claude-sonnet-5","prompt_version":"v3",
                   "retrieved_chunk_ids":[101,102],"retry_count":0,
                   "retrieval":{"k":12},"degraded":%s,"degraded_reason":%s},
                 "system_log":{}}
                """.formatted(degraded, degraded ? "\"low_evidence\"" : "null");
    }

    private HttpServer stubServer(AtomicReference<String> seenBody, AtomicReference<String> seenAuth,
                                   String responseBody) throws java.io.IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/assess", exchange -> {
            seenAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            var bytes = responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test void sendsTheSnakeCaseRequestAndParsesARealResponse() throws Exception {
        var seenBody = new AtomicReference<String>();
        var seenAuth = new AtomicReference<String>();
        var server = stubServer(seenBody, seenAuth, assessResponseBody(false));
        try {
            var restClient = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-token").build();
            var client = new AiAssessmentClient(restClient);

            var response = client.assess(request());

            assertEquals("Bearer test-token", seenAuth.get());
            var body = json.readTree(seenBody.get());
            assertEquals("req-1", body.path("request_id").asText());
            assertTrue(body.has("user_context"));
            assertEquals("B", response.evidenceGrade());
            assertEquals(1, response.evidenceFindings().size());
            assertEquals("battery", response.evidenceFindings().getFirst().factor());
            assertEquals(List.of(101L, 102L), response.meta().retrievedChunkIds());
            assertFalse(response.meta().degraded());
        } finally { server.stop(0); }
    }

    @Test void aDegradedButSuccessfulResponseStillParsesCleanly() throws Exception {
        // The class's own Javadoc: the AI service returns 200 on every degraded path,
        // never a 5xx - this must not be mistaken for a failure.
        var seenBody = new AtomicReference<String>();
        var seenAuth = new AtomicReference<String>();
        var server = stubServer(seenBody, seenAuth, assessResponseBody(true));
        try {
            var restClient = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
            var client = new AiAssessmentClient(restClient);

            var response = client.assess(request());

            assertTrue(response.meta().degraded());
            assertEquals("low_evidence", response.meta().degradedReason());
        } finally { server.stop(0); }
    }

    @Test void aGenuineServiceOutageThrowsAiServiceExceptionNotASilentDegrade() throws Exception {
        // A real outage/unreachable service is a different failure mode from
        // meta.degraded and must not be conflated with it - confirmed by pointing
        // at a port nothing is listening on.
        var restClient = RestClient.builder().baseUrl("http://127.0.0.1:1").build();
        var client = new AiAssessmentClient(restClient);

        var error = assertThrows(AiServiceException.class, () -> client.assess(request()));
        assertNotNull(error.getCause());
    }

    @Test void doesNotDropAnAlreadyConfiguredAuthorizationHeader() throws Exception {
        var seenBody = new AtomicReference<String>();
        var seenAuth = new AtomicReference<String>();
        var server = stubServer(seenBody, seenAuth, assessResponseBody(false));
        try {
            var restClient = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer secret-value").build();
            new AiAssessmentClient(restClient).assess(request());

            assertEquals("Bearer secret-value", seenAuth.get());
        } finally { server.stop(0); }
    }
}
