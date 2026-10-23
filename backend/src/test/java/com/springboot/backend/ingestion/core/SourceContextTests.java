package com.springboot.backend.ingestion.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SourceContextTests {
    @Test void customRequestAndExecutionBudgetsAreValidatedAndApplied() {
        var client = mock(HttpClient.class);
        try (var context = new SourceContext(
                Clock.systemUTC(), () -> {}, client, 63, Duration.ofSeconds(93))) {
            assertEquals(63, context.remainingRequests());
        }
        assertThrows(IllegalArgumentException.class, () ->
                new SourceContext(Clock.systemUTC(), () -> {}, client, 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () ->
                new SourceContext(Clock.systemUTC(), () -> {}, client, 1, Duration.ZERO));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={400,401,403,500,502})
    @SuppressWarnings("unchecked")
    void httpStatusIsSanitizedAndMapped(int status) throws Exception {
        var client = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(new ByteArrayInputStream("secret-key upstream details".getBytes()));
        try (var context = new SourceContext(Clock.systemUTC(), () -> {}, client)) {
            var settings = new com.springboot.backend.ingestion.searchapi.SearchApiSettings("secret-key", "sg", "en", "Singapore", 1);
            var api = new com.springboot.backend.ingestion.searchapi.SearchApiClient(settings);
            var failure = assertThrows(IngestionFailure.class, () -> api.shopping(context, "Apple iPhone 16 Pro"));
            assertEquals(status == 400 ? IngestionFailure.Code.SEARCHAPI_HTTP_400
                    : status == 401 || status == 403 ? IngestionFailure.Code.SEARCHAPI_AUTH_FAILED
                    : IngestionFailure.Code.SEARCHAPI_HTTP_FAILED, failure.code());
            assertNull(failure.getCause()); assertFalse(failure.toString().contains("secret-key"));
        }
    }

    @Test @SuppressWarnings("unchecked") void transportFailureHasNoSensitiveCause() throws Exception {
        var client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new HttpTimeoutException("Authorization: secret-key"));
        try (var context = new SourceContext(Clock.systemUTC(), () -> {}, client)) {
            var failure = assertThrows(SourceContext.TransportFailure.class,
                    () -> context.get(URI.create("https://www.searchapi.io"), "secret-key", "www.searchapi.io"));
            assertNull(failure.getCause()); assertFalse(failure.toString().contains("secret-key"));
        }
    }
    @Test @SuppressWarnings("unchecked") void bearerRequestUsesBoundedTransportAndNeverLeaksErrors() throws Exception {
        var client = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        when(response.statusCode()).thenReturn(400);
        when(response.body()).thenReturn(new ByteArrayInputStream("invalid product_token secret-key".getBytes()));
        try (var context = new SourceContext(Clock.systemUTC(), () -> {}, client)) {
            var failure = assertThrows(SourceContext.HttpFailure.class, () -> context.get(
                    URI.create("https://www.searchapi.io/api/v1/search?engine=google_shopping"), "secret-key", "www.searchapi.io"));
            assertTrue(failure.invalidToken); assertFalse(failure.toString().contains("secret-key"));
            assertNull(failure.getCause());
            var request = ArgumentCaptor.forClass(HttpRequest.class);
            verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
            assertEquals("Bearer secret-key", request.getValue().headers().firstValue("Authorization").orElseThrow());
            assertFalse(request.getValue().uri().toString().contains("secret-key"));
            assertEquals(20, request.getValue().timeout().orElseThrow().toSeconds());
            assertThrows(IllegalArgumentException.class, () -> context.get(URI.create("https://evil.example"), "secret-key", "www.searchapi.io"));
            assertThrows(IllegalArgumentException.class, () -> context.get(URI.create("http://www.searchapi.io"), "secret-key", "www.searchapi.io"));
        }
        verify(client).shutdownNow();
    }

    @Test @SuppressWarnings("unchecked") void retryAfterDefersAndCancelledSourceStops() throws Exception {
        var client = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        when(response.statusCode()).thenReturn(429);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Retry-After", java.util.List.of("60")), (a,b) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(new byte[0]));
        try (var context = new SourceContext(Clock.systemUTC(), () -> {}, client)) {
            assertThrows(SourceContext.RetryLater.class, () -> context.get(URI.create("https://www.searchapi.io")));
            context.close();
            assertThrows(IllegalStateException.class, () -> context.get(URI.create("https://www.searchapi.io")));
            verify(client, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        }
    }

    @SuppressWarnings("unchecked")
    private static HttpClient clientReturning(int... statuses) throws Exception {
        var responses = new java.util.ArrayList<HttpResponse<InputStream>>();
        for (int status : statuses) {
            HttpResponse<InputStream> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(status);
            when(response.headers()).thenReturn(HttpHeaders.of(
                    Map.of("Retry-After", java.util.List.of("0")), (a, b) -> true));
            when(response.body()).thenAnswer(i -> new ByteArrayInputStream(("body-" + status).getBytes()));
            responses.add(response);
        }
        var client = mock(HttpClient.class);
        var stubbing = when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)));
        for (var response : responses) stubbing = stubbing.thenReturn(response);
        return client;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {404, 500})
    void fetchReturnsNonSuccessStatusWithBodyInsteadOfThrowing(int status) throws Exception {
        try (var context = new SourceContext(Clock.systemUTC(), () -> {}, clientReturning(status))) {
            var response = context.fetch(URI.create("https://example.com/robots.txt"));
            assertEquals(status, response.status());
            assertEquals("body-" + status, new String(response.body()));
        }
    }

    @Test void getStillThrowsHttpFailureOnNotFound() throws Exception {
        try (var context = new SourceContext(Clock.systemUTC(), () -> {}, clientReturning(404))) {
            var failure = assertThrows(SourceContext.HttpFailure.class,
                    () -> context.get(URI.create("https://example.com/missing")));
            assertEquals(404, failure.status);
            assertFalse(failure.invalidToken);
        }
    }

    @Test @SuppressWarnings("unchecked") void fetchRetriesRateLimitAndCountsEveryAttempt() throws Exception {
        var client = clientReturning(429, 200);
        try (var context = new SourceContext(Clock.systemUTC(), () -> {}, client, 5, Duration.ofSeconds(30))) {
            var response = context.fetch(URI.create("https://example.com/page"));
            assertEquals(200, response.status());
            assertEquals("body-200", new String(response.body()));
            assertEquals(3, context.remainingRequests());
            verify(client, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        }
    }

    @Test @SuppressWarnings("unchecked") void everyRequestIdentifiesWithRobotsAgentUserAgent() throws Exception {
        assertTrue(SourceContext.USER_AGENT.startsWith(SourceContext.ROBOTS_AGENT + "/"));
        var client = clientReturning(200);
        try (var context = new SourceContext(Clock.systemUTC(), () -> {}, client)) {
            context.fetch(URI.create("https://example.com/robots.txt"));
            var request = ArgumentCaptor.forClass(HttpRequest.class);
            verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
            assertEquals(SourceContext.USER_AGENT,
                    request.getValue().headers().firstValue("User-Agent").orElseThrow());
        }
    }

    @Test void minimumIntervalSlowsOnlyTheConfiguredHost() throws Exception {
        try (var context = new SourceContext(Clock.systemUTC(), () -> {}, clientReturning(200, 200, 200))) {
            context.minimumInterval("Slow.Example.com", Duration.ofMillis(1500));
            context.fetch(URI.create("https://slow.example.com/a"));
            long start = System.nanoTime();
            context.fetch(URI.create("https://slow.example.com/b"));
            long sameHostMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertTrue(sameHostMillis >= 1450, "same-host gap was " + sameHostMillis + " ms");

            start = System.nanoTime();
            context.fetch(URI.create("https://other.example.com/c"));
            long otherHostMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertTrue(otherHostMillis >= 950, "global pacing gap was " + otherHostMillis + " ms");
            assertTrue(otherHostMillis < 1450, "other-host gap was " + otherHostMillis + " ms");
        }
    }

    @Test void minimumIntervalRejectsInvalidArguments() {
        try (var context = new SourceContext(Clock.systemUTC(), () -> {}, mock(HttpClient.class))) {
            assertThrows(IllegalArgumentException.class, () -> context.minimumInterval(null, Duration.ofSeconds(1)));
            assertThrows(IllegalArgumentException.class,
                    () -> context.minimumInterval("example.com", Duration.ofMillis(-1)));
            assertThrows(IllegalArgumentException.class, () -> context.minimumInterval("example.com", null));
        }
    }

    @Test @SuppressWarnings("unchecked") void fetchRejectsNonHttpsTargets() throws Exception {
        var client = mock(HttpClient.class);
        try (var context = new SourceContext(Clock.systemUTC(), () -> {}, client)) {
            assertThrows(IllegalArgumentException.class, () -> context.fetch(URI.create("http://example.com/robots.txt")));
            assertThrows(IllegalArgumentException.class, () -> context.fetch(URI.create("https://example.com:8443/x")));
            assertThrows(IllegalArgumentException.class, () -> context.fetch(URI.create("ftp://example.com/x")));
            verify(client, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
            assertEquals(IngestionSource.DEFAULT_REQUEST_LIMIT, context.remainingRequests());
        }
    }
}
