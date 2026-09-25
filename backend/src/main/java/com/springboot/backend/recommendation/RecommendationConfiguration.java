package com.springboot.backend.recommendation;

import com.springboot.backend.recommendation.classification.ScoringSettings;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({AiSettings.class, ScoringSettings.class})
public class RecommendationConfiguration {

    /**
     * One RestClient talking to the FastAPI AI service. Bearer token per
     * AGENTS.md §9/§20.3; omitted entirely when {@code AI_SERVICE_TOKEN} is
     * blank, matching the AI service's own "blank disables auth" behaviour
     * (ai/app/main.py::require_token) so local dev needs no token on either
     * side.
     */
    @Bean
    RestClient aiRestClient(AiSettings settings) {
        // HTTP/1.1 explicitly: the JDK client defaults to HTTP/2 and attempts an
        // h2c upgrade on cleartext, which uvicorn/h11 does not support - the body
        // is then dropped and FastAPI answers 422 "Field required, loc: body".
        var httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(settings.timeout())
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(settings.timeout());

        var configured = RestClient.builder().baseUrl(settings.serviceUrl()).requestFactory(requestFactory);
        if (settings.serviceToken() != null && !settings.serviceToken().isBlank()) {
            configured = configured.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + settings.serviceToken());
        }
        return configured.build();
    }
}
