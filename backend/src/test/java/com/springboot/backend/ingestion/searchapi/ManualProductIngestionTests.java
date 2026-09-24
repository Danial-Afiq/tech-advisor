package com.springboot.backend.ingestion.searchapi;

import com.springboot.backend.ingestion.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties={"ingestion.demo-password=test-password-only", "ingestion.reconciliation-enabled=false",
        "ingestion.enabled-sources=searchapi-google-product-reviews,simulated-release", "searchapi.api-key=test-key-only",
        "logging.level.root=WARN", "debug=false"})
@ActiveProfiles("ingestion-demo")
class ManualProductIngestionTests {
    @Autowired JdbcTemplate db;
    @Autowired RunStore store;
    @Autowired WebApplicationContext web;
    @MockitoBean SearchApiClient api;
    MockMvc mvc;
    long first, second;
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach void setup() throws Exception {
        assertTrue(db.queryForObject("SELECT current_database()", String.class).endsWith("_test"));
        db.update("DELETE FROM system_log WHERE component LIKE 'INGESTION_%'");
        db.update("DELETE FROM products WHERE brand IN ('ManualTargetTest', 'OtherTargetTest', 'AutoCreateTest')");
        first = insert("ManualTargetTest", "Earlier Phone", "SMARTPHONE", "VERIFIED");
        second = insert("ManualTargetTest", "Later Phone", "SMARTPHONE", "VERIFIED");
        mvc = MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
        when(api.shopping(any(), any())).thenAnswer(inv -> json.valueToTree(java.util.List.of(
                Map.of("title", inv.getArgument(1, String.class), "product_id", "mock-product", "product_token", "mock-token"))));
        when(api.reviews(any(), any(), any())).thenReturn(json.createArrayNode());
    }
    @AfterEach void cleanup() {
        db.update("DELETE FROM products WHERE brand IN ('ManualTargetTest', 'OtherTargetTest', 'AutoCreateTest')");
    }

    @Test void validatesAndCreatesAnUnknownSmartphoneBeforeImportingReviews() throws Exception {
        String result = mvc.perform(post("/api/admin/ingestion/runs").with(user("admin").roles("ADMIN")).with(csrf())
                .header("Idempotency-Key", "auto-create-key").contentType("application/json")
                .content(body("AutoCreateTest New Phone")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.product.productId").isEmpty())
                .andExpect(jsonPath("$.product.productName").value("AutoCreateTest New Phone"))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(result).path("runId").asText();
        await(id);
        assertEquals("SUCCESS", store.get(id).status);
        var product = db.queryForMap("""
                SELECT p.id,p.brand,p.model_name,p.category,p.status,
                       EXISTS (SELECT 1 FROM phone ph WHERE ph.product_id=p.id) has_phone,
                       EXISTS (SELECT 1 FROM external_product_mapping m WHERE m.product_id=p.id
                               AND m.status='VALID') has_mapping
                FROM products p WHERE p.brand='AutoCreateTest' AND p.model_name='New Phone'
                """);
        assertEquals("SMARTPHONE", product.get("category")); assertEquals("VERIFIED", product.get("status"));
        assertEquals(true, product.get("has_phone")); assertEquals(true, product.get("has_mapping"));
        verify(api).shopping(any(), eq("AutoCreateTest New Phone"));

        // Retrying the same request remains idempotent after discovery supplied a database ID.
        mvc.perform(post("/api/admin/ingestion/runs").with(user("admin").roles("ADMIN")).with(csrf())
                .header("Idempotency-Key", "auto-create-key").contentType("application/json")
                .content(body("AutoCreateTest New Phone")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.runId").value(id));
    }

    @Test void failedProviderValidationCreatesNoCatalogueRows() throws Exception {
        doReturn(json.createArrayNode()).when(api).shopping(any(), eq("NoMatchBrand Missing Phone"));
        String result = mvc.perform(post("/api/admin/ingestion/runs").with(user("admin").roles("ADMIN")).with(csrf())
                .header("Idempotency-Key", "no-match-create-key").contentType("application/json")
                .content(body("NoMatchBrand Missing Phone")))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(result).path("runId").asText();
        await(id);
        assertEquals("FAILED", store.get(id).status);
        assertEquals(0, db.queryForObject("SELECT count(*) FROM products WHERE brand='NoMatchBrand'",
                Integer.class));
    }
    long insert(String brand, String model, String category, String status) {
        return db.queryForObject("INSERT INTO products(brand,model_name,category,status) VALUES (?,?,?,?) RETURNING id",
                Long.class, brand, model, category, status);
    }
    String body(String name) {
        return json.writeValueAsString(Map.of("sources", java.util.List.of(SearchApiSource.ID), "productName", name));
    }

    @Test void resolvesNamePersistsTargetAndExecutesOnlyTheRequestedProduct() throws Exception {
        String result = mvc.perform(post("/api/admin/ingestion/runs").with(user("admin").roles("ADMIN")).with(csrf())
                .header("Idempotency-Key", "targeted-run-key").contentType("application/json")
                .content(body("  manualtargettest   LATER Phone  ")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.product.productId").value(second))
                .andExpect(jsonPath("$.product.productName").value("ManualTargetTest Later Phone"))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(result).path("runId").asText();
        await(id);
        var reloaded = store.get(id);
        assertEquals(second, reloaded.product.productId()); assertEquals("SUCCESS", reloaded.status);
        verify(api).shopping(any(), eq("ManualTargetTest Later Phone"));
        verify(api, never()).shopping(any(), eq("ManualTargetTest Earlier Phone"));
        verify(api).reviews(any(), eq("mock-token"), eq("most_relevant"));
        verify(api).reviews(any(), eq("mock-token"), eq("most_recent"));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM external_product_mapping WHERE product_id=?", Integer.class, first));

        // A retry resolves to the same canonical identity; changing products conflicts.
        mvc.perform(post("/api/admin/ingestion/runs").with(user("admin").roles("ADMIN")).with(csrf())
                .header("Idempotency-Key", "targeted-run-key").contentType("application/json").content(body("Later Phone")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.runId").value(id));
        mvc.perform(post("/api/admin/ingestion/runs").with(user("admin").roles("ADMIN")).with(csrf())
                .header("Idempotency-Key", "targeted-run-key").contentType("application/json").content(body("Earlier Phone")))
                .andExpect(status().isConflict());
    }

    @Test void rejectsAmbiguousIneligibleAndMalformedNamesBeforeAnyApiCall() throws Exception {
        insert("OtherTargetTest", "Later Phone", "SMARTPHONE", "VERIFIED");
        insert("ManualTargetTest", "Unverified Phone", "SMARTPHONE", "UNVERIFIED");
        insert("ManualTargetTest", "Graphics Card", "GPU", "VERIFIED");
        for (String name : java.util.List.of("Unknown", "Later Phone", "Unverified Phone", "Graphics Card", " ", "x".repeat(201))) {
            mvc.perform(post("/api/admin/ingestion/runs").with(user("admin").roles("ADMIN")).with(csrf())
                    .header("Idempotency-Key", "rejected-run-key").contentType("application/json").content(body(name)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());
        }
        mvc.perform(post("/api/admin/ingestion/runs").with(user("admin").roles("ADMIN")).with(csrf())
                .header("Idempotency-Key", "wrong-source-key").contentType("application/json")
                .content("{\"sources\":[\"simulated-release\"],\"productName\":\"Later Phone\"}"))
                .andExpect(status().isBadRequest());
        assertTrue(store.history(0, 20, "", "").isEmpty());
        verifyNoInteractions(api);
    }

    @Test void targetSurvivesAdmissionBeforeDispatchAndChangedProductsFailClosed() throws Exception {
        var target = new RunLog.ProductTarget(second, "ManualTargetTest Later Phone");
        var admitted = store.admit(java.util.List.of(SearchApiSource.ID), "admin", "durable-target-key", null, false, false, target);
        assertEquals(target, store.claim("resumed-worker").product);
        db.update("UPDATE products SET status='UNVERIFIED' WHERE id=?", second);
        var source = web.getBean(SearchApiSource.class);
        try (var context = new SourceContext(java.time.Clock.systemUTC(), () -> {}, store.get(admitted.runId).product)) {
            var failure = assertThrows(IngestionFailure.class, () -> source.ingest(context, p -> fail("Unexpected payload")));
            assertEquals(IngestionFailure.Code.SEARCHAPI_NO_ELIGIBLE_PRODUCT, failure.code());
        }
        verifyNoInteractions(api);
    }

    private void await(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (store.get(id).finishedAt != null) { Thread.sleep(50); return; }
            Thread.sleep(50);
        }
        fail("Run did not finish");
    }
}
