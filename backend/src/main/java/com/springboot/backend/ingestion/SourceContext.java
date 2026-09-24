package com.springboot.backend.ingestion;

import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Cooperative deadline plus bounded HTTP access. All adapters must use this for network reads. */
public final class SourceContext implements AutoCloseable {
    public static final class RetryLater extends RuntimeException {
        public final Instant until;
        RetryLater(Instant until) { super("Source requested a later retry"); this.until = until; }
    }
    private final Clock clock;
    private final Runnable ownershipCheck;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    private long lastRequest;
    private int requests;
    private final HttpClient http;
    private final RunLog.ProductTarget product;

    public SourceContext(Clock clock, Runnable ownershipCheck) {
        this(clock, ownershipCheck, (RunLog.ProductTarget) null);
    }
    public static final class TransportFailure extends RuntimeException {
        TransportFailure() { super("Source transport failed"); }
    }
    public SourceContext(Clock clock, Runnable ownershipCheck, RunLog.ProductTarget product) {
        this(clock, ownershipCheck, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build(), product);
    }
    SourceContext(Clock clock, Runnable ownershipCheck, HttpClient http) {
        this(clock, ownershipCheck, http, null);
    }
    private SourceContext(Clock clock, Runnable ownershipCheck, HttpClient http, RunLog.ProductTarget product) {
        this.clock = clock; this.ownershipCheck = ownershipCheck; this.http = http; this.product = product;
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
        if (!"https".equals(url.getScheme()) || url.getHost() == null
                || url.getUserInfo() != null || url.getFragment() != null
                || (url.getPort() != -1 && url.getPort() != 443)
                || (bearer != null && (!url.getHost().equals(allowedHost)
                    || bearer.isBlank() || bearer.contains("\r") || bearer.contains("\n"))))
            throw new IllegalArgumentException("Invalid source HTTPS target or credentials");
        for (int attempt = 0; attempt < 3; attempt++) {
            check();
            if (++requests > 10) throw new IllegalStateException("Source request limit exceeded");
            long delay = 1000 - Duration.ofNanos(System.nanoTime() - lastRequest).toMillis();
            if (delay > 0) Thread.sleep(delay);
            check(); lastRequest = System.nanoTime();
            var builder = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "TechAdvisor-Ingestion/1.0");
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
                if (response.statusCode() != 200) {
                    String error = new String(bytes, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                    boolean invalidToken = response.statusCode() == 400 && error.contains("product_token")
                            && (error.contains("invalid") || error.contains("expired"));
                    throw new HttpFailure(response.statusCode(), invalidToken);
                }
                check(); return bytes;
            }
        }
        throw new IllegalStateException("Source unavailable");
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
