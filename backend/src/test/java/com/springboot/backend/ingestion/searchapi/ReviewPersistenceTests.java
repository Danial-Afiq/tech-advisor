package com.springboot.backend.ingestion.searchapi;

import com.springboot.backend.ingestion.*;
import com.springboot.backend.ingestion.reviews.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties={"ingestion.reconciliation-enabled=false", "ingestion.enabled-sources=",
        "logging.level.root=WARN", "debug=false"})
class ReviewPersistenceTests {
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;
    @Autowired SearchApiRepository mappings;
    ReviewEmbeddingClient embedding;
    ReviewBatchSink sink;
    long product;
    boolean retainSmokeFixture;
    final Instant now = Instant.parse("2026-09-24T00:00:00Z");
    @BeforeEach void setup() {
        assertTrue(db.queryForObject("SELECT current_database()", String.class).endsWith("_test"));
        product = db.queryForObject("INSERT INTO products(brand,model_name) VALUES ('SearchApiTest',?) RETURNING id",
                Long.class, UUID.randomUUID().toString());
        embedding = mock(ReviewEmbeddingClient.class);
        sink = new ReviewBatchSink(db, embedding, manager);
        when(embedding.embed(any())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return new ReviewEmbeddingClient.Embeddings("minishlab/potion-retrieval-32M", 512,
                    texts.stream().map(t -> vector()).toList());
        });
    }
    @AfterEach void cleanup() {
        if (!retainSmokeFixture) db.update("DELETE FROM products WHERE brand='SearchApiTest'");
    }
    List<Double> vector() {
        var v = new ArrayList<>(Collections.nCopies(512, 0.0)); v.set(0, 1.0); return v;
    }
    Payload payload(String... texts) {
        var reviews = Arrays.stream(texts).map(t -> new Payload.Review(
                ReviewNormalizer.fingerprint(product, "walmart.com", "", t, BigDecimal.valueOf(5)),
                "walmart.com", "", t, BigDecimal.valueOf(5), "5 months ago", now)).toList();
        return new Payload(SearchApiSource.ID, Long.toString(product), now, new Payload.ReviewBatch(product, reviews));
    }
    int count(String table) {
        return db.queryForObject("SELECT count(*) FROM " + table + " WHERE "
                + (table.equals("review_documents") ? "product_id=?" : "review_document_id IN (SELECT id FROM review_documents WHERE product_id=?)"), Integer.class, product);
    }
    /** Explicit local opt-in, uses real FastAPI/model but a mocked SearchAPI response. */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="REVIEW_SMOKE_AI_URL", matches=".+")
    void realEmbeddingSmokeFixture() throws Exception {
        var real = new ReviewEmbeddingClient(org.springframework.web.client.RestClient.builder()
                .baseUrl(System.getenv("REVIEW_SMOKE_AI_URL"))
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(
                        java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build()))
                .defaultHeader("Authorization", "Bearer " + System.getenv("REVIEW_SMOKE_AI_TOKEN")).build(),
                "minishlab/potion-retrieval-32M");
        var durable = new ReviewBatchSink(db, real, manager);
        var repository = mock(SearchApiRepository.class);
        var client = mock(SearchApiClient.class);
        var canonical = new SearchApiRepository.Product(product, "Apple", "iPhone 16 Pro");
        when(repository.products(1)).thenReturn(List.of(canonical));
        when(repository.token(any(), any())).thenReturn(Optional.empty());
        var json = JsonMapper.builder().build();
        when(client.shopping(any(), any())).thenReturn(json.readTree("""
                [{"title":"Apple iPhone 16 Pro 256GB","product_id":"fixture","product_token":"fixture"}]
                """));
        when(client.reviews(any(), any(), any())).thenReturn(json.readTree("""
                [{"source":"walmart.com","rating":5,"date":"5 months ago","username":"OMIT_THIS_PROFILE",
                  "text":"Battery life lasts significantly longer than my previous phone."},
                 {"source":"walmart.com","rating":4,"date":"6 months ago",
                  "text":"The camera captures detailed photos in low light."}]
                """));
        var source = new SearchApiSource(new SearchApiSettings("fixture", "sg", "en", "Singapore", 1),
                repository, client, new IngestionSettings(false, null, List.of()));
        try (var context = new SourceContext(Clock.systemUTC(), () -> {})) {
            source.ingest(context, p -> assertEquals(IngestionSink.Result.ACCEPTED, durable.accept("smoke", p, context)));
            source.ingest(context, p -> assertEquals(IngestionSink.Result.DUPLICATE, durable.accept("smoke-rerun", p, context)));
        }
        assertEquals(2, count("review_documents")); assertEquals(2, count("review_chunks"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/searchapi-smoke-product.txt"), Long.toString(product));
        retainSmokeFixture = true; // caller runs the read-only Python verifier, then removes this fixture
    }
    @Test void atomicBatchRerunAndProvenance() {
        long unrelated = db.queryForObject("INSERT INTO review_documents(product_id,source_name) VALUES (?, 'manual') RETURNING id", Long.class, product);
        var payload = payload("Battery life lasts significantly longer than my previous phone.", "The camera struggles at night.");
        assertEquals(IngestionSink.Result.ACCEPTED, sink.accept("run-one", payload));
        assertEquals(3, count("review_documents")); assertEquals(2, count("review_chunks"));
        assertEquals(IngestionSink.Result.DUPLICATE, sink.accept("run-two", payload));
        verify(embedding, times(1)).embed(any());
        assertEquals(3, count("review_documents")); assertEquals(2, count("review_chunks"));
        var rows = db.queryForList("""
                SELECT d.product_id,d.source_name,d.published_at,d.metadata::text,c.embedder,vector_dims(c.embedding) dims
                FROM review_documents d JOIN review_chunks c ON c.review_document_id=d.id WHERE d.product_id=?
                """, product);
        for (var row : rows) {
            assertNull(row.get("published_at"));
            assertEquals("minishlab/potion-retrieval-32M", row.get("embedder")); assertEquals(512, row.get("dims"));
            assertTrue(row.get("source_name").toString().contains("Google Shopping reviews via SearchAPI"));
            var metadata = JsonMapper.builder().build().readTree(row.get("metadata").toString());
            assertEquals(Set.of("source_domain", "rating", "raw_date", "retrieved_at"), new HashSet<>(metadata.propertyNames()));
            assertEquals("5 months ago", metadata.path("raw_date").asText());
        }
        assertEquals(1, db.queryForObject("SELECT count(*) FROM review_documents WHERE id=?", Integer.class, unrelated));
    }
    @Test void embeddingFailureWritesNothing() {
        doThrow(new IngestionFailure(IngestionFailure.Code.EMBEDDING_FAILED)).when(embedding).embed(any());
        assertThrows(IngestionFailure.class, () -> sink.accept("run", payload("Battery lasts all day with gaming.")));
        assertEquals(0, count("review_documents")); assertEquals(0, count("review_chunks"));
    }
    @Test void failedSecondChunkRollsBackFirstDocumentAndChunk() {
        doReturn(new ReviewEmbeddingClient.Embeddings("test", 512,
                List.of(vector(), List.of(1.0)))).when(embedding).embed(any()); // force a DB width failure after the first insert
        assertThrows(Exception.class, () -> sink.accept("run", payload("Battery lasts all day with gaming.", "Camera is excellent in the dark.")));
        assertEquals(0, count("review_documents")); assertEquals(0, count("review_chunks"));
    }
    @Test void cancellationAfterEmbeddingWritesNothing() {
        var context = mock(SourceContext.class);
        doNothing().doThrow(new IllegalStateException("cancelled")).when(context).check();
        assertThrows(IllegalStateException.class, () -> sink.accept("run", payload("Battery lasts all day with gaming."), context));
        assertEquals(0, count("review_documents"));
    }
    @Test void tokenCacheIsLocaleAndCanonicalIdentityScopedAndSelectionIsOrdered() {
        var p = new SearchApiRepository.Product(product, "SearchApiTest", "model");
        var s = new SearchApiSettings("", "sg", "en", "Singapore", 1);
        mappings.cache(p, s, new ProductMatcher.Match("google-id", "cached", "Matched title"), now);
        assertEquals(Optional.of("cached"), mappings.token(p, s));
        assertTrue(mappings.token(new SearchApiRepository.Product(product, "SearchApiTest", "changed"), s).isEmpty());
        assertTrue(mappings.token(p, new SearchApiSettings("", "us", "en", "USA", 1)).isEmpty());
        mappings.invalidate(p, s); assertTrue(mappings.token(p, s).isEmpty());
        var ids = mappings.products(2).stream().map(SearchApiRepository.Product::id).toList();
        assertEquals(ids.stream().sorted().toList(), ids);
        db.update("UPDATE products SET status='UNVERIFIED' WHERE id=?", product);
        assertFalse(mappings.products(10000).stream().anyMatch(row -> row.id() == product));
        db.update("UPDATE products SET status='VERIFIED',category='GPU' WHERE id=?", product);
        assertFalse(mappings.products(10000).stream().anyMatch(row -> row.id() == product));
    }
}
