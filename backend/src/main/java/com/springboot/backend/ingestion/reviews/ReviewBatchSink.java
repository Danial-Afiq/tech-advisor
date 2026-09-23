package com.springboot.backend.ingestion.reviews;

import com.springboot.backend.ingestion.*;
import com.springboot.backend.ingestion.searchapi.SearchApiSource;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ReviewBatchSink implements IngestionSink {
    public static final String PROVIDER = "SEARCHAPI_GOOGLE_SHOPPING";
    private final JdbcTemplate db;
    private final ReviewEmbeddingClient embeddings;
    private final TransactionTemplate transaction;
    private final JsonMapper json = JsonMapper.builder().build();
    public ReviewBatchSink(JdbcTemplate db, ReviewEmbeddingClient embeddings, PlatformTransactionManager manager) {
        this.db = db; this.embeddings = embeddings; this.transaction = new TransactionTemplate(manager);
        transaction.setTimeout(10);
    }
    @Override public boolean supports(IngestionSource source, Payload.Body body) {
        return source.sourceId().equals(SearchApiSource.ID) && body instanceof Payload.ReviewBatch;
    }
    @Override public Result accept(String runId, Payload payload) { return persist(payload, () -> {}); }
    @Override public Result accept(String runId, Payload payload, SourceContext context) {
        return persist(payload, context::check);
    }
    private Result persist(Payload payload, Runnable check) {
        payload.validate(SearchApiSource.ID);
        var batch = (Payload.ReviewBatch) payload.body();
        Set<String> existing = new HashSet<>(db.query("""
                SELECT external_fingerprint FROM review_documents
                WHERE product_id=? AND provider=?
                """, (r, n) -> r.getString(1), batch.productId(), PROVIDER));
        var fresh = batch.reviews().stream().filter(r -> !existing.contains(r.fingerprint())).toList();
        if (fresh.isEmpty()) return Result.DUPLICATE;
        check.run();
        // All network work completes before opening the transaction.
        var vectors = embeddings.embed(fresh.stream().map(Payload.Review::text).toList());
        check.run();
        return transaction.execute(status -> {
            // Lock identity while writing and recheck eligibility after external calls.
            var eligible = db.queryForList("""
                    SELECT id FROM products WHERE id=? AND category='SMARTPHONE' AND status='VERIFIED' FOR SHARE
                    """, Long.class, batch.productId());
            if (eligible.isEmpty()) throw new IllegalStateException("Review product no longer eligible");
            int inserted = 0;
            for (int i = 0; i < fresh.size(); i++) {
                if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Review write cancelled");
                var review = fresh.get(i);
                String metadata = json.writeValueAsString(Map.of("source_domain", review.sourceDomain(),
                        "rating", review.rating(), "raw_date", review.rawDate(),
                        "retrieved_at", review.retrievedAt().toString()));
                var ids = db.query("""
                        INSERT INTO review_documents
                          (product_id,source_name,title,published_at,ingested_at,provider,external_fingerprint,metadata)
                        VALUES (?,?,?,NULL,?,?,?,?::jsonb)
                        ON CONFLICT (product_id,provider,external_fingerprint) DO NOTHING RETURNING id
                        """, (r, n) -> r.getLong(1), batch.productId(),
                        "Google Shopping reviews via SearchAPI / " + review.sourceDomain(), review.title(),
                        Timestamp.from(review.retrievedAt()), PROVIDER, review.fingerprint(), metadata);
                if (ids.isEmpty()) continue;
                String vector = vectors.vectors().get(i).stream().map(Object::toString).collect(Collectors.joining(",", "[", "]"));
                db.update("""
                        INSERT INTO review_chunks(review_document_id,chunk_index,chunk_text,embedding,embedder)
                        VALUES (?,0,?,?::vector,?)
                        """, ids.getFirst(), review.text(), vector, vectors.embedder());
                inserted++;
            }
            check.run();
            return inserted == 0 ? Result.DUPLICATE : Result.ACCEPTED;
        });
    }
}
