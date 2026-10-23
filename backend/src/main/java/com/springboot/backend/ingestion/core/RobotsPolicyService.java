package com.springboot.backend.ingestion.core;

import crawlercommons.robots.BaseRobotRules;
import crawlercommons.robots.SimpleRobotRules;
import crawlercommons.robots.SimpleRobotRulesParser;
import java.net.URI;
import java.time.*;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * robots.txt gate for scraped sources. It only decides; callers must not request a URL
 * unless the decision is {@link Outcome#ALLOWED}. The robots.txt fetch goes through the
 * run's {@link SourceContext} so it counts against the same request cap and pacing.
 */
@Component
public class RobotsPolicyService {
    private static final Logger LOG = LoggerFactory.getLogger(RobotsPolicyService.class);
    static final Duration CACHE_TTL = Duration.ofHours(24);
    /** Polite floor when robots.txt states no Crawl-delay. */
    static final Duration DEFAULT_CRAWL_DELAY = Duration.ofSeconds(5);
    /** crawler-commons matches product tokens case-insensitively but expects lower-case input. */
    private static final List<String> AGENTS = List.of(SourceContext.ROBOTS_AGENT.toLowerCase(Locale.ROOT));

    /** ROBOTS_UNAVAILABLE is treated as disallowed: no rules means no permission. */
    public enum Outcome { ALLOWED, ROBOTS_DISALLOWED, ROBOTS_UNAVAILABLE }
    public record Decision(Outcome outcome, Duration crawlDelay) {
        public boolean allowed() { return outcome == Outcome.ALLOWED; }
    }
    private record Policy(BaseRobotRules rules, Duration crawlDelay, Instant fetchedAt) {}

    private final Clock clock;
    private final SimpleRobotRulesParser parser = new SimpleRobotRulesParser();
    private final Map<String, Policy> cache = new ConcurrentHashMap<>();

    public RobotsPolicyService(Clock clock) { this.clock = clock; }

    /**
     * Cancellation, deadline and request-cap failures propagate so the run stops rather
     * than treating them as an unavailable robots.txt.
     */
    public Decision check(SourceContext context, URI url) throws Exception {
        if (url == null || !"https".equals(url.getScheme()) || url.getHost() == null)
            throw new IllegalArgumentException("Robots check requires an https URL");
        String host = url.getHost().toLowerCase(Locale.ROOT);
        Policy policy = cache.get(host);
        if (policy == null || !clock.instant().isBefore(policy.fetchedAt().plus(CACHE_TTL))) {
            policy = load(context, host);
            if (policy == null) {
                context.minimumInterval(host, DEFAULT_CRAWL_DELAY);
                return new Decision(Outcome.ROBOTS_UNAVAILABLE, DEFAULT_CRAWL_DELAY);
            }
            cache.put(host, policy);
        }
        // Each run has a fresh SourceContext, so the delay is reapplied even on a cache hit.
        context.minimumInterval(host, policy.crawlDelay());
        return new Decision(policy.rules().isAllowed(url.toString())
                ? Outcome.ALLOWED : Outcome.ROBOTS_DISALLOWED, policy.crawlDelay());
    }

    /** Null means unavailable, which is deliberately not cached so the next run retries. */
    private Policy load(SourceContext context, String host) throws Exception {
        String robotsUrl = "https://" + host + "/robots.txt";
        SourceContext.Response response;
        try { response = context.fetch(URI.create(robotsUrl)); }
        catch (SourceContext.TransportFailure | SourceContext.RetryLater e) {
            LOG.warn("robots.txt unavailable for host {}: {}", host, e.getClass().getSimpleName());
            return null;
        }
        Instant now = clock.instant();
        // 404/410 conventionally mean no restrictions.
        if (response.status() == 404 || response.status() == 410)
            return new Policy(new SimpleRobotRules(SimpleRobotRules.RobotRulesMode.ALLOW_ALL),
                    DEFAULT_CRAWL_DELAY, now);
        if (response.status() != 200) {
            LOG.warn("robots.txt unavailable for host {}: HTTP {}", host, response.status());
            return null;
        }
        var rules = parser.parseContent(robotsUrl, response.body(), "text/plain", AGENTS);
        long delayMillis = rules.getCrawlDelay();
        Duration delay = delayMillis == BaseRobotRules.UNSET_CRAWL_DELAY || delayMillis < 0
                ? DEFAULT_CRAWL_DELAY : Duration.ofMillis(delayMillis);
        return new Policy(rules, delay, now);
    }
}
