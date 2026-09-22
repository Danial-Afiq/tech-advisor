package com.springboot.backend.recommendation;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Thin wrapper around {@code POST /assess}. Section 9 of the LLM layer spec.
 * The AI service returns 200 on every degraded path (never a 5xx) - a
 * {@link RestClientException} here means the service itself is unreachable
 * or broken, a genuinely different failure from {@code meta.degraded}, and
 * callers must not conflate the two.
 */
@Component
public class AiAssessmentClient {
    private final RestClient client;

    public AiAssessmentClient(RestClient aiRestClient) {
        this.client = aiRestClient;
    }

    public AssessResponse assess(AssessRequest request) {
        try {
            return client.post()
                    .uri("/assess")
                    // Explicit, not incidental: without it FastAPI does not bind the
                    // body at all and answers 422 "Field required, loc: body".
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(AssessResponse.class);
        } catch (RestClientException e) {
            throw new AiServiceException("AI service call failed: " + e.getMessage(), e);
        }
    }
}
