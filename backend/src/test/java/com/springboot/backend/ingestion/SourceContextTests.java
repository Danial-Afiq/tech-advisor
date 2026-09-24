package com.springboot.backend.ingestion;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SourceContextTests {
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
}
