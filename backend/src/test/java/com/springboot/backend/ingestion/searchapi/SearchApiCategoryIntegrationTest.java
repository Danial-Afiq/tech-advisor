package com.springboot.backend.ingestion.searchapi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.springboot.backend.ingestion.core.IngestionSink;
import com.springboot.backend.ingestion.core.Payload;
import com.springboot.backend.ingestion.reviews.ReviewBatchSink;
import com.springboot.backend.ingestion.reviews.ReviewEmbeddingClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Ticket 1.3: which catalogue rows SearchAPI may pick and write reviews for follows
 * searchapi.categories. Rows are branded MARK so the test owns them in the shared _test database.
 */
@SpringBootTest(properties = "ingestion.reconciliation-enabled=false")
class SearchApiCategoryIntegrationTest {
    private static final String MARK = "SearchApiCategoryTest";
    // A locale no other test uses, so earlier mapping rows never hide these products.
    private final SearchApiSettings phonesOnly = new SearchApiSettings("", "zz", "en", "Nowhere", 3);
    private final SearchApiSettings phonesAndGpus =
            new SearchApiSettings("", "zz", "en", "Nowhere", 3, List.of("SMARTPHONE", "GPU"));

    @Autowired SearchApiRepository repository;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;
    long phone;
    long gpu;

    @BeforeEach
    void insertProducts() {
        assertTrue(db.queryForObject("SELECT current_database()", String.class).endsWith("_test"),
                "Integration tests require a dedicated database whose name ends in _test");
        cleanUp();
        phone = db.queryForObject("INSERT INTO products (brand, model_name, category) VALUES (?, 'Phone One', 'SMARTPHONE') RETURNING id",
                Long.class, MARK);
        gpu = db.queryForObject("INSERT INTO products (brand, model_name, category) VALUES (?, 'Graphics One', 'GPU') RETURNING id",
                Long.class, MARK);
    }

    @AfterEach
    void cleanUp() {
        db.update("DELETE FROM external_product_mapping WHERE gl = 'zz'");
        db.update("DELETE FROM products WHERE brand = ?", MARK);
    }

    @Test
    void untargetedSelectionIncludesGpusOnlyWhenConfigured() {
        // products() skips rows already tried in this locale; mark every other product tried in
        // the test-only "zz" locale so only this test's two rows are left to pick.
        db.update("""
                INSERT INTO external_product_mapping (product_id, provider, gl, hl, location, external_product_id,
                    matched_title, canonical_name, matched_at, last_verified_at, status)
                SELECT id, ?, 'zz', 'en', 'Nowhere', 'NO_MATCH_FOUND', 'NO_MATCH_FOUND', brand || ' ' || model_name,
                       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'INVALID'
                FROM products WHERE brand <> ?
                """, SearchApiSource.PROVIDER, MARK);

        assertEquals(List.of(phone), ids(repository.products(phonesOnly)));
        assertEquals(List.of(phone, gpu), ids(repository.products(phonesAndGpus)));
    }

    @Test
    void targetedLookupsCarryTheCategoryAndRespectTheSetting() {
        assertTrue(repository.eligibleProduct(gpu, phonesOnly).isEmpty());
        var found = repository.eligibleProduct(gpu, phonesAndGpus).orElseThrow();
        assertEquals("GPU", found.category());
        assertEquals("SMARTPHONE", repository.eligibleProduct(phone, phonesOnly).orElseThrow().category());

        assertEquals(List.of(), repository.namedProducts(MARK + " Graphics One", phonesOnly));
        assertEquals(List.of(gpu), ids(repository.namedProducts(MARK + " Graphics One", phonesAndGpus)));
    }

    @Test
    void reviewsForAGpuAreStoredOnlyWhenGpusAreEnabled() {
        var embedding = mock(ReviewEmbeddingClient.class);
        when(embedding.embed(any())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            var vector = new ArrayList<>(Collections.nCopies(512, 0.0)); vector.set(0, 1.0);
            return new ReviewEmbeddingClient.Embeddings("minishlab/potion-retrieval-32M", 512,
                    texts.stream().map(text -> (List<Double>) vector).toList());
        });
        Instant now = Instant.parse("2026-10-23T00:00:00Z");
        String text = "Runs cool and quiet at 1440p, a big step up from my old card.";
        var review = new Payload.Review(ReviewNormalizer.fingerprint(gpu, "newegg.com", "", text, BigDecimal.valueOf(5)),
                "newegg.com", "", text, BigDecimal.valueOf(5), "2 weeks ago", now);
        var payload = new Payload(SearchApiSource.ID, Long.toString(gpu), now, new Payload.ReviewBatch(gpu, List.of(review)));

        assertThrows(IllegalStateException.class,
                () -> new ReviewBatchSink(db, embedding, manager, phonesOnly).accept("phones-only", payload));
        assertEquals(IngestionSink.Result.ACCEPTED,
                new ReviewBatchSink(db, embedding, manager, phonesAndGpus).accept("with-gpus", payload));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM review_documents WHERE product_id = ?", Integer.class, gpu));
    }

    private static List<Long> ids(List<SearchApiRepository.Product> products) {
        return products.stream().map(SearchApiRepository.Product::id).toList();
    }
}
