package com.springboot.backend.ingestion.reviews;

import com.springboot.backend.ingestion.IngestionFailure;
import static com.springboot.backend.ingestion.IngestionFailure.Code.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ReviewEmbeddingClient {
    private final RestClient client;
    private final String expectedEmbedder;
    public record Embeddings(String embedder, int dimension, List<List<Double>> vectors) {}
    public ReviewEmbeddingClient(RestClient aiRestClient,
            @Value("${ai.ingestion-embedder:minishlab/potion-retrieval-32M}") String expectedEmbedder) {
        this.client = aiRestClient; this.expectedEmbedder = expectedEmbedder;
    }
    public Embeddings embed(List<String> texts) {
        Embeddings result;
        try {
            result = client.post().uri("/internal/embed").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("texts", texts)).retrieve().body(Embeddings.class);
        } catch (Exception e) { throw new IngestionFailure(EMBEDDING_FAILED); }
        if (result == null || !expectedEmbedder.equals(result.embedder()) || result.dimension() != 512
                || result.vectors() == null || result.vectors().size() != texts.size())
            throw new IngestionFailure(EMBEDDING_CONTRACT_MISMATCH);
        for (var vector : result.vectors()) {
            if (vector == null || vector.size() != 512
                    || vector.stream().anyMatch(v -> v == null || !Double.isFinite(v) || Math.abs(v) > Float.MAX_VALUE)
                    || vector.stream().allMatch(v -> v == 0))
                throw new IngestionFailure(EMBEDDING_CONTRACT_MISMATCH);
        }
        return result;
    }
}
