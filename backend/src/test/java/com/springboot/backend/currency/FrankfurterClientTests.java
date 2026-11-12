package com.springboot.backend.currency;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real socket, not a mock - same pattern as EmbeddingClientTests. latestRates() is the one
 * place FrankfurterClient's own request-building and response-parsing logic lives, and
 * CurrencyConversionServiceTest mocks FrankfurterClient entirely, so nothing else ever
 * exercises it. The V2-vs-V1 response shape distinction (see latestRates()'s own comment) is
 * exactly the kind of thing a mock can never catch regressing.
 */
class FrankfurterClientTests {
    @Test void parsesTheRealV2FlatArrayShapeAndSendsTheExpectedQuery() throws Exception {
        var seenPath = new AtomicReference<String>();
        var response = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/rates", exchange -> {
            seenPath.set(exchange.getRequestURI().toString());
            var bytes = response.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var client = new FrankfurterClient(
                RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build());
        try {
            response.set("""
                    [{"date":"2026-11-10","base":"SGD","quote":"USD","rate":0.74},
                     {"date":"2026-11-10","base":"SGD","quote":"EUR","rate":0.68}]
                    """);

            var rates = client.latestRates();

            assertTrue(seenPath.get().contains("base=SGD"), "expected ?base=SGD in: " + seenPath.get());
            assertEquals(2, rates.size());
            assertEquals("USD", rates.get(0).quote());
            assertEquals(0, new java.math.BigDecimal("0.74").compareTo(rates.get(0).rate()));
        } finally { server.stop(0); }
    }

    @Test void emptyArrayIsAValidZeroRateResult() throws Exception {
        var server = stubReturning("[]");
        var client = new FrankfurterClient(
                RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build());
        try {
            assertTrue(client.latestRates().isEmpty());
        } finally { server.stop(0); }
    }

    // latestRates() currently has no explicit handling for this - documents the real,
    // currently-uncategorized behavior (a raw RestClientException) rather than silently
    // asserting nothing. If FrankfurterClient later adopts an IngestionFailure-style code the
    // way SearchApiClient does, this assertion is exactly what should change.
    @Test void aNonJsonBodyThrowsAnUncategorizedRestClientException() throws Exception {
        var server = stubReturning("not json");
        var client = new FrankfurterClient(
                RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build());
        try {
            assertThrows(org.springframework.web.client.RestClientException.class, client::latestRates);
        } finally { server.stop(0); }
    }

    private HttpServer stubReturning(String body) throws java.io.IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/rates", exchange -> {
            var bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }
}
