package com.springboot.backend.ingestion.searchapi;

import com.springboot.backend.ingestion.IngestionFailure;
import com.springboot.backend.ingestion.reviews.ReviewEmbeddingClient;
import com.sun.net.httpserver.HttpServer;
import static org.junit.jupiter.api.Assertions.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class EmbeddingClientTests {
    @Test void batchesAndRejectsWrongSpaceWidthAndTransport() throws Exception {
        var response = new AtomicReference<String>(); var seen = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/embed", exchange -> {
            assertEquals("Bearer test-token", exchange.getRequestHeaders().getFirst("Authorization"));
            seen.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            var bytes = response.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        var client = new ReviewEmbeddingClient(RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .defaultHeader("Authorization", "Bearer test-token").build(), "expected");
        var vector = new ArrayList<>(Collections.nCopies(512, 0.0)); vector.set(0, 1.0);
        var json = JsonMapper.builder().build();
        try {
            response.set(json.writeValueAsString(Map.of("embedder", "expected", "dimension", 512, "vectors", List.of(vector, vector))));
            assertEquals(2, client.embed(List.of("battery", "camera")).vectors().size());
            assertEquals(2, json.readTree(seen.get()).path("texts").size());
            for (String body : List.of("{}", "{\"embedder\":\"wrong\",\"dimension\":512,\"vectors\":[]}",
                    "{\"embedder\":\"expected\",\"dimension\":2,\"vectors\":[[1,0]]}")) {
                response.set(body); assertThrows(IngestionFailure.class, () -> client.embed(List.of("battery")));
            }
        } finally { server.stop(0); }
        var failure = assertThrows(IngestionFailure.class, () -> client.embed(List.of("battery")));
        assertNull(failure.getCause()); assertFalse(failure.toString().contains("test-token"));
    }
}
