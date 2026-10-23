package com.springboot.backend.ingestion.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.springboot.backend.ingestion.core.RobotsPolicyService.Decision;
import com.springboot.backend.ingestion.core.RobotsPolicyService.Outcome;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Each check that must fetch uses a fresh SourceContext (as each run does) so the
 * context's one-second and per-host pacing never makes these tests sleep.
 */
class RobotsPolicyServiceTest {
    private static final String TECHSPOT = """
            User-agent: *
            Disallow: /specs/compare/
            Disallow: /products/prices/
            Disallow: /search
            """;
    private static final String GPU = "https://www.techspot.com/specs/gpu/289555-nvidia-geforce-rtx-4070-super.html";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-23T00:00:00Z"));
    private HttpClient http;
    private RobotsPolicyService service;

    @BeforeEach void setUp() {
        http = mock(HttpClient.class);
        service = new RobotsPolicyService(clock);
    }

    @Test void allowsSpecPageNotCoveredByAnyRule() throws Exception {
        respond(200, TECHSPOT);
        Decision decision = service.check(context(), URI.create(GPU));
        assertEquals(Outcome.ALLOWED, decision.outcome());
        assertTrue(decision.allowed());
    }

    @Test void disallowsComparePricesAndSearchPaths() throws Exception {
        respond(200, TECHSPOT);
        var context = context();
        assertEquals(Outcome.ROBOTS_DISALLOWED,
                service.check(context, URI.create("https://www.techspot.com/specs/compare/")).outcome());
        assertEquals(Outcome.ROBOTS_DISALLOWED,
                service.check(context, URI.create("https://www.techspot.com/products/prices/123")).outcome());
        assertEquals(Outcome.ROBOTS_DISALLOWED,
                service.check(context, URI.create("https://www.techspot.com/search?q=4070")).outcome());
        assertFalse(service.check(context, URI.create("https://www.techspot.com/products/prices/")).allowed());
        verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test void honoursWildcardAndEndAnchoredRules() throws Exception {
        respond(200, TECHSPOT + "Disallow: /*.pdf$\nDisallow: /community/*/post-\n");
        var context = context();
        assertEquals(Outcome.ROBOTS_DISALLOWED,
                service.check(context, URI.create("https://www.techspot.com/files/review.pdf")).outcome());
        assertEquals(Outcome.ALLOWED,
                service.check(context, URI.create("https://www.techspot.com/files/review.pdf.html")).outcome());
        assertEquals(Outcome.ROBOTS_DISALLOWED,
                service.check(context, URI.create("https://www.techspot.com/community/gpus/post-42")).outcome());
        assertEquals(Outcome.ALLOWED,
                service.check(context, URI.create("https://www.techspot.com/community/gpus/")).outcome());
    }

    @Test void agentSpecificGroupOverridesWildcardGroup() throws Exception {
        respond(200, """
                User-agent: TechAdvisorBot
                Disallow: /specs/
                Crawl-delay: 3

                """ + TECHSPOT);
        var context = context();
        Decision gpu = service.check(context, URI.create(GPU));
        assertEquals(Outcome.ROBOTS_DISALLOWED, gpu.outcome());
        assertEquals(Duration.ofSeconds(3), gpu.crawlDelay());
        // The * group's /search rule does not apply once a specific group matches.
        assertEquals(Outcome.ALLOWED, service.check(context, URI.create("https://www.techspot.com/search")).outcome());
    }

    @Test void statedCrawlDelayIsUsed() throws Exception {
        respond(200, TECHSPOT + "Crawl-delay: 7\n");
        assertEquals(Duration.ofSeconds(7), service.check(context(), URI.create(GPU)).crawlDelay());
    }

    @Test void crawlDelayDefaultsToFiveSeconds() throws Exception {
        respond(200, TECHSPOT);
        assertEquals(RobotsPolicyService.DEFAULT_CRAWL_DELAY, service.check(context(), URI.create(GPU)).crawlDelay());
        assertEquals(Duration.ofSeconds(5), RobotsPolicyService.DEFAULT_CRAWL_DELAY);
    }

    @Test void missingRobotsAllowsAllAndIsCached() throws Exception {
        respond(404, "not found");
        Decision first = service.check(context(), URI.create("https://www.techspot.com/search"));
        Decision second = service.check(context(), URI.create("https://www.techspot.com/specs/compare/"));
        assertEquals(Outcome.ALLOWED, first.outcome());
        assertEquals(Outcome.ALLOWED, second.outcome());
        assertEquals(Duration.ofSeconds(5), first.crawlDelay());
        verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test void goneRobotsAllowsAll() throws Exception {
        respond(410, "");
        assertEquals(Outcome.ALLOWED, service.check(context(), URI.create(GPU)).outcome());
    }

    @Test void serverErrorIsUnavailableAndNotCached() throws Exception {
        respond(500, "oops");
        assertEquals(Outcome.ROBOTS_UNAVAILABLE, service.check(context(), URI.create(GPU)).outcome());
        Decision retry = service.check(context(), URI.create(GPU));
        assertEquals(Outcome.ROBOTS_UNAVAILABLE, retry.outcome());
        assertFalse(retry.allowed());
        verify(http, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test void otherNon200StatusIsUnavailable() throws Exception {
        respond(403, "forbidden");
        assertEquals(Outcome.ROBOTS_UNAVAILABLE, service.check(context(), URI.create(GPU)).outcome());
    }

    @Test @SuppressWarnings("unchecked") void transportFailureIsUnavailableAndNotCached() throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("connection reset"))
                .thenReturn(response(200, TECHSPOT));
        assertEquals(Outcome.ROBOTS_UNAVAILABLE, service.check(context(), URI.create(GPU)).outcome());
        assertEquals(Outcome.ALLOWED, service.check(context(), URI.create(GPU)).outcome());
        verify(http, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test @SuppressWarnings("unchecked") void longRetryAfterIsUnavailable() throws Exception {
        HttpResponse<InputStream> response = response(503, "");
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Retry-After", List.of("120")), (k, v) -> true));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        assertEquals(Outcome.ROBOTS_UNAVAILABLE, service.check(context(), URI.create(GPU)).outcome());
    }

    @Test void successIsCachedFor24HoursThenRefetched() throws Exception {
        respond(200, TECHSPOT);
        service.check(context(), URI.create(GPU));
        clock.advance(Duration.ofHours(23).plusMinutes(59));
        service.check(context(), URI.create(GPU));
        verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        clock.advance(Duration.ofMinutes(2));
        service.check(context(), URI.create(GPU));
        verify(http, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test @SuppressWarnings("unchecked") void fetchesRobotsAtLowercasedHostRootThroughContext() throws Exception {
        respond(200, TECHSPOT);
        var context = context();
        int before = context.remainingRequests();
        service.check(context, URI.create("https://WWW.TechSpot.com/specs/gpu/x.html"));
        service.check(context, URI.create("https://www.techspot.com/specs/gpu/y.html"));
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(1)).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals(URI.create("https://www.techspot.com/robots.txt"), request.getValue().uri());
        assertEquals(before - 1, context.remainingRequests());
    }

    @Test void rejectsNonHttpsUrls() {
        var context = context();
        assertThrows(IllegalArgumentException.class, () -> service.check(context, URI.create("http://www.techspot.com/")));
        assertThrows(IllegalArgumentException.class, () -> service.check(context, URI.create("/specs/gpu/")));
        verifyNoInteractions(http);
    }

    @Test void cancellationPropagates() {
        var cancelled = new SourceContext(clock, () -> { throw new IllegalStateException("cancelled"); }, http);
        assertThrows(IllegalStateException.class, () -> service.check(cancelled, URI.create(GPU)));
    }

    private SourceContext context() { return new SourceContext(clock, () -> {}, http); }

    @SuppressWarnings("unchecked")
    private void respond(int status, String body) throws Exception {
        HttpResponse<InputStream> response = response(status, body);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }

    /** A fresh body stream per read, so one mock response can serve repeated sends. */
    @SuppressWarnings("unchecked")
    private static HttpResponse<InputStream> response(int status, String body) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenAnswer(i -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return response;
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
