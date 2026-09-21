package com.springboot.backend.recommendation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection details for the FastAPI AI service's {@code POST /assess}.
 * {@code serviceToken} is the Spring half of the shared secret described in
 * AGENTS.md §20.3/§20.1 ({@code AI_SERVICE_TOKEN}); a blank token is a valid
 * local-development state and simply sends no Authorization header, matching
 * {@code ai/app/main.py::require_token} treating a blank server-side token as
 * "auth disabled".
 */
@ConfigurationProperties("ai")
public record AiSettings(String serviceUrl, String serviceToken, Duration timeout) {
    public AiSettings {
        if (serviceUrl == null || serviceUrl.isBlank())
            throw new IllegalArgumentException("AI_SERVICE_URL is required");
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
    }
}
