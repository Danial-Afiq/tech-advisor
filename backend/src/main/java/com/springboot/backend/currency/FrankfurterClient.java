package com.springboot.backend.currency;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class FrankfurterClient {
    private final RestClient client;

    public FrankfurterClient() {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder()
                .baseUrl("https://api.frankfurter.dev")
                .requestFactory(requestFactory)
                .build();
    }

    FrankfurterClient(RestClient client) {
        this.client = client;
    }

    List<Rate> latestRates() {
        Rate[] response = client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v2/rates")
                        .queryParam("base", "SGD")
                        .build())
                .retrieve()
                .body(Rate[].class);

        if (response == null) {
            throw new IllegalStateException("Frankfurter returned no rates");
        }
        return Arrays.asList(response);
    }

    record Rate(LocalDate date, String base, String quote, BigDecimal rate) {
    }
}
