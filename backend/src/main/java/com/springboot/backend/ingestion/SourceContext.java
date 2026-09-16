package com.springboot.backend.ingestion;

import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public SourceContext(Clock clock, Runnable ownershipCheck) { this.clock = clock; this.ownershipCheck = ownershipCheck; }
    public Instant now() { return clock.instant(); }
    public void check() {
        if (closed.get() || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
            throw new IllegalStateException("Source cancelled or deadline exceeded");
        ownershipCheck.run();
    }
    public byte[] get(URI url) throws Exception {
        if (!"https".equals(url.getScheme())) throw new IllegalArgumentException("Sources require HTTPS");
        for (int attempt = 0; attempt < 3; attempt++) {
            check();
            if (++requests > 10) throw new IllegalStateException("Source request limit exceeded");
            long delay = 1000 - Duration.ofNanos(System.nanoTime() - lastRequest).toMillis();
            if (delay > 0) Thread.sleep(delay);
            check(); lastRequest = System.nanoTime();
            var request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "TechAdvisor-Ingestion/1.0").GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() == 429 || response.statusCode() == 503) {
                    long wait = response.headers().firstValue("Retry-After").map(this::retrySeconds).orElse(1L << attempt);
                    if (wait > 15 || attempt == 2) throw new RetryLater(now().plusSeconds(Math.min(wait, 31_536_000)));
                    Thread.sleep(Math.max(1, wait) * 1000); continue;
                }
                if (response.statusCode() != 200) throw new IllegalStateException("Source HTTP failure");
                byte[] bytes = body.readNBytes(1_048_577);
                if (bytes.length > 1_048_576) throw new IllegalStateException("Source response exceeds 1 MiB");
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
