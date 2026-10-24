package com.springboot.backend.ingestion.core;

import com.springboot.backend.ingestion.run.RunLog;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Cooperative deadline plus bounded HTTP access. All adapters must use this for network reads. */
public final class SourceContext implements AutoCloseable {
    /** Identifies our crawler to the sites it reads. */
    public static final String BOT_NAME = "TechAdvisorBot";
    public static final String USER_AGENT =
            BOT_NAME + "/1.0 (+https://github.com/Danial-Afiq/tech-advisor)";
    public static final class RetryLater extends RuntimeException {
        public final Instant until;
        RetryLater(Instant until) { super("Source requested a later retry"); this.until = until; }
    }
    private final Clock clock;
    private final Runnable ownershipCheck;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final long deadline;
    private final int requestLimit;
    private long lastRequest;
    private int requests;
    private final Map<String, Duration> hostIntervals = new HashMap<>();
    private final Map<String, Long> lastHostRequest = new HashMap<>();
    private final HttpClient http;
    private final RunLog.ProductTarget product;

    public SourceContext(Clock clock, Runnable ownershipCheck) {
        this(clock, ownershipCheck, (RunLog.ProductTarget) null);
    }
    public static final class TransportFailure extends RuntimeException {
        public TransportFailure() { super("Source transport failed"); }
    }
    public SourceContext(Clock clock, Runnable ownershipCheck, RunLog.ProductTarget product) {
        this(clock, ownershipCheck, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build(), product,
                IngestionSource.DEFAULT_REQUEST_LIMIT, IngestionSource.DEFAULT_EXECUTION_BUDGET);
    }
    SourceContext(Clock clock, Runnable ownershipCheck, RunLog.ProductTarget product,
                  int requestLimit, Duration executionBudget) {
        this(clock, ownershipCheck, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build(), product, requestLimit, executionBudget);
    }
    SourceContext(Clock clock, Runnable ownershipCheck, HttpClient http) {
        this(clock, ownershipCheck, http, null,
                IngestionSource.DEFAULT_REQUEST_LIMIT, IngestionSource.DEFAULT_EXECUTION_BUDGET);
    }
    SourceContext(Clock clock, Runnable ownershipCheck, HttpClient http,
                  int requestLimit, Duration executionBudget) {
        this(clock, ownershipCheck, http, null, requestLimit, executionBudget);
    }
    private SourceContext(Clock clock, Runnable ownershipCheck, HttpClient http,
                          RunLog.ProductTarget product, int requestLimit, Duration executionBudget) {
        if (requestLimit < 1 || executionBudget == null
                || executionBudget.isZero() || executionBudget.isNegative())
            throw new IllegalArgumentException("Source limits must be positive");
        this.clock = clock; this.ownershipCheck = ownershipCheck; this.http = http; this.product = product;
        this.requestLimit = requestLimit;
        this.deadline = System.nanoTime() + executionBudget.toNanos();
    }
    public RunLog.ProductTarget product() { return product; }
    public static final class HttpFailure extends RuntimeException {
        public final int status;
        public final boolean invalidToken;
        HttpFailure(int status, boolean invalidToken) {
            super("Source HTTP status " + status); this.status = status; this.invalidToken = invalidToken;
        }
    }
    public Instant now() { return clock.instant(); }
    public int remainingRequests() { return Math.max(0, requestLimit - requests); }
    public void check() {
        if (closed.get() || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
            throw new IllegalStateException("Source cancelled or deadline exceeded");
        ownershipCheck.run();
    }
    public byte[] get(URI url) throws Exception {
        return get(url, null, null);
    }
    /** Credentials are scoped to a trusted exact host; redirects remain disabled. */
    public byte[] get(URI url, String bearer, String allowedHost) throws Exception {
        var response = send(url, bearer, allowedHost);
        if (response.status() != 200) {
            String error = new String(response.body(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            boolean invalidToken = response.status() == 400 && error.contains("product_token")
                    && (error.contains("invalid") || error.contains("expired"));
            throw new HttpFailure(response.status(), invalidToken);
        }
        return response.body();
    }
    private record Response(int status, byte[] body) {
        // A record's generated equals/hashCode/toString compare arrays by reference.
        @Override public boolean equals(Object o) {
            return o instanceof Response r && status == r.status && Arrays.equals(body, r.body);
        }
        @Override public int hashCode() { return 31 * Integer.hashCode(status) + Arrays.hashCode(body); }
        @Override public String toString() { return "Response[status=" + status + ", body=" + body.length + " bytes]"; }
    }
    /**
     * Slows later requests to one host, e.g. to stay polite to a scraped site.
     * Applies on top of the global one-second pacing and never shortens it.
     */
    public void minimumInterval(String host, Duration interval) {
        if (host == null || interval == null || interval.isNegative())
            throw new IllegalArgumentException("Invalid host interval");
        hostIntervals.put(host.toLowerCase(Locale.ROOT), interval);
    }
    private Response send(URI url, String bearer, String allowedHost) throws Exception {
        if (!"https".equals(url.getScheme()) || url.getHost() == null
                || url.getUserInfo() != null || url.getFragment() != null
                || (url.getPort() != -1 && url.getPort() != 443)
                || (bearer != null && (!url.getHost().equals(allowedHost)
                    || bearer.isBlank() || bearer.contains("\r") || bearer.contains("\n"))))
            throw new IllegalArgumentException("Invalid source HTTPS target or credentials");
        String host = url.getHost().toLowerCase(Locale.ROOT);
        for (int attempt = 0; attempt < 3; attempt++) {
            check();
            if (++requests > requestLimit) throw new IllegalStateException("Source request limit exceeded");
            long delay = Math.max(1000 - Duration.ofNanos(System.nanoTime() - lastRequest).toMillis(), hostDelay(host));
            if (delay > 0) Thread.sleep(delay);
            check(); lastRequest = System.nanoTime(); lastHostRequest.put(host, lastRequest);
            var builder = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(20))
                    .header("User-Agent", USER_AGENT);
            if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
            HttpResponse<InputStream> response;
            try { response = http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Source interrupted"); }
            catch (Exception e) { throw new TransportFailure(); }
            try (InputStream body = response.body()) {
                if (response.statusCode() == 429 || response.statusCode() == 503) {
                    long wait = response.headers().firstValue("Retry-After").map(this::retrySeconds).orElse(1L << attempt);
                    if (wait > 15 || attempt == 2) throw new RetryLater(now().plusSeconds(Math.min(wait, 31_536_000)));
                    Thread.sleep(Math.max(1, wait) * 1000); continue;
                }
                byte[] bytes;
                try { bytes = body.readNBytes(1_048_577); }
                catch (Exception e) { throw new TransportFailure(); }
                if (bytes.length > 1_048_576) throw new IllegalStateException("Source response exceeds 1 MiB");
                check(); return new Response(response.statusCode(), bytes);
            }
        }
        throw new IllegalStateException("Source unavailable");
    }
    private long hostDelay(String host) {
        var interval = hostIntervals.get(host);
        var last = lastHostRequest.get(host);
        if (interval == null || last == null) return 0;
        return interval.toMillis() - Duration.ofNanos(System.nanoTime() - last).toMillis();
    }
    private long retrySeconds(String value) {
        try { return Math.max(0, Long.parseLong(value)); }
        catch (NumberFormatException e) {
            try { return Math.max(0, Duration.between(now(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds()); }
            catch (RuntimeException invalid) { return 2; }
        }
    }
    @Override public void close() { closed.set(true); http.shutdownNow(); }
}
