package com.springboot.backend;

import com.springboot.backend.model.Product;
import com.springboot.backend.model.User;
import com.springboot.backend.repository.PhoneRepository;
import com.springboot.backend.repository.ProductRepository;
import com.springboot.backend.repository.UserRepository;
import com.springboot.backend.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "ingestion.reconciliation-enabled=false",
        "ingestion.scheduling-enabled=false",
        "logging.level.root=WARN"
})
@Transactional
class SmartphoneCatalogueIntegrationTest {

    @Autowired WebApplicationContext web;
    @Autowired JwtService jwtService;
    @Autowired UserRepository users;
    @Autowired ProductRepository products;
    @Autowired PhoneRepository phones;
    @Autowired JdbcTemplate jdbc;

    private MockMvc mvc;
    private String adminToken;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(web)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        User admin = users.save(new User(
                "catalogue-admin-" + UUID.randomUUID() + "@example.test",
                "{noop}unused",
                "ADMIN"
        ));
        adminToken = jwtService.generateToken(admin);
    }

    @Test
    void adminCanCreateReadUpdateAndDeleteSmartphone() throws Exception {
        String model = "Test Phone " + UUID.randomUUID();

        mvc.perform(MockMvcRequestBuilders.post("/api/admin/catalogue/smartphones")
                        .header("Authorization", bearer(adminToken))
                        .contentType("application/json")
                        .content(phoneJson("Test Brand", model, "Test Chip", 8, 256)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.brand").value("Test Brand"))
                .andExpect(jsonPath("$.modelName").value(model))
                .andExpect(jsonPath("$.chipset").value("Test Chip"))
                .andExpect(jsonPath("$.ramGb").value(8))
                .andExpect(jsonPath("$.storageGb").value(256));

        Product product = products.findByBrandAndModelName("Test Brand", model).orElseThrow();
        assertEquals(Product.CATEGORY_SMARTPHONE, product.getCategory());
        assertTrue(phones.findById(product.getId()).isPresent());

        mvc.perform(MockMvcRequestBuilders.get("/api/catalogue/smartphones/{id}", product.getId())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(product.getId()))
                .andExpect(jsonPath("$.modelName").value(model));

        mvc.perform(MockMvcRequestBuilders.get("/api/catalogue/smartphones")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %s)]", product.getId()).exists());

        mvc.perform(MockMvcRequestBuilders.put("/api/admin/catalogue/smartphones/{id}", product.getId())
                        .header("Authorization", bearer(adminToken))
                        .contentType("application/json")
                        .content(phoneJson("Updated Brand", model, "Updated Chip", 12, 512)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brand").value("Updated Brand"))
                .andExpect(jsonPath("$.chipset").value("Updated Chip"))
                .andExpect(jsonPath("$.ramGb").value(12))
                .andExpect(jsonPath("$.storageGb").value(512));

        assertEquals("Updated Brand", products.findById(product.getId()).orElseThrow().getBrand());
        assertEquals(12, phones.findById(product.getId()).orElseThrow().getRamGb());

        mvc.perform(MockMvcRequestBuilders.delete("/api/admin/catalogue/smartphones/{id}", product.getId())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isNoContent());

        assertFalse(products.existsById(product.getId()));
        assertFalse(phones.existsById(product.getId()));
    }

    @Test
    void invalidInputDoesNotCreateEitherRow() throws Exception {
        String model = "Invalid Phone " + UUID.randomUUID();
        long productCount = products.count();
        long phoneCount = phones.count();

        mvc.perform(MockMvcRequestBuilders.post("/api/admin/catalogue/smartphones")
                        .header("Authorization", bearer(adminToken))
                        .contentType("application/json")
                        .content(phoneJson("Test Brand", model, "Test Chip", -1, 256)))
                .andExpect(status().isBadRequest());

        assertTrue(products.findByBrandAndModelName("Test Brand", model).isEmpty());
        assertEquals(productCount, products.count());
        assertEquals(phoneCount, phones.count());
    }

    @Test
    void readEndpointsAllowUsersAndAdminsButRejectAnonymousRequests() throws Exception {
        User user = users.save(new User(
                "catalogue-user-" + UUID.randomUUID() + "@example.test",
                "{noop}unused",
                "USER"
        ));
        String userToken = jwtService.generateToken(user);
        String model = "Readable Phone " + UUID.randomUUID();

        mvc.perform(MockMvcRequestBuilders.post("/api/admin/catalogue/smartphones")
                        .header("Authorization", bearer(adminToken))
                        .contentType("application/json")
                        .content(phoneJson("Test Brand", model, "Test Chip", 8, 256)))
                .andExpect(status().isCreated());

        Product product = products.findByBrandAndModelName("Test Brand", model).orElseThrow();

        for (String token : new String[] {adminToken, userToken}) {
            mvc.perform(MockMvcRequestBuilders.get("/api/catalogue/smartphones")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.id == %s)]", product.getId()).exists());

            mvc.perform(MockMvcRequestBuilders.get("/api/catalogue/smartphones/{id}", product.getId())
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(product.getId()))
                    .andExpect(jsonPath("$.brand").value("Test Brand"))
                    .andExpect(jsonPath("$.chipset").value("Test Chip"));
        }

        mvc.perform(MockMvcRequestBuilders.get("/api/catalogue/smartphones"))
                .andExpect(status().isUnauthorized());
        mvc.perform(MockMvcRequestBuilders.get("/api/catalogue/smartphones/{id}", product.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listAndReadIncludeTheLatestPriceOrNull() throws Exception {
        String priced = "Priced Phone " + UUID.randomUUID();
        String unpriced = "Unpriced Phone " + UUID.randomUUID();
        for (String model : new String[] {priced, unpriced}) {
            mvc.perform(MockMvcRequestBuilders.post("/api/admin/catalogue/smartphones")
                            .header("Authorization", bearer(adminToken))
                            .contentType("application/json")
                            .content(phoneJson("Test Brand", model, "Test Chip", 8, 256)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.latestPrice").value(nullValue()));
        }
        long pricedId = products.findByBrandAndModelName("Test Brand", priced).orElseThrow().getId();
        long unpricedId = products.findByBrandAndModelName("Test Brand", unpriced).orElseThrow().getId();

        // Older, newer, and a same-timestamp tie that the higher id wins.
        String insert = "INSERT INTO price_history (product_id, price, currency, observed_at) VALUES (?, ?, ?, ?::timestamptz)";
        jdbc.update(insert, pricedId, 999.00, "SGD", "2026-01-01T00:00:00Z");
        jdbc.update(insert, pricedId, 849.00, "SGD", "2026-03-01T00:00:00Z");
        jdbc.update(insert, pricedId, 829.50, "SGD", "2026-03-01T00:00:00Z");

        mvc.perform(MockMvcRequestBuilders.get("/api/catalogue/smartphones")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %s)].latestPrice", pricedId).value(829.5))
                .andExpect(jsonPath("$[?(@.id == %s)].currency", pricedId).value("SGD"))
                .andExpect(jsonPath("$[?(@.id == %s)].latestPrice", unpricedId).value(hasItem(nullValue())));

        mvc.perform(MockMvcRequestBuilders.get("/api/catalogue/smartphones/{id}", pricedId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(jsonPath("$.latestPrice").value(829.5));

        mvc.perform(MockMvcRequestBuilders.put("/api/admin/catalogue/smartphones/{id}", pricedId)
                        .header("Authorization", bearer(adminToken))
                        .contentType("application/json")
                        .content(phoneJson("Test Brand", priced, "New Chip", 12, 512)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestPrice").value(829.5))
                .andExpect(jsonPath("$.currency").value("SGD"));
    }

    @Test
    void writeEndpointsRequireAdminRole() throws Exception {
        User user = users.save(new User(
                "catalogue-user-" + UUID.randomUUID() + "@example.test",
                "{noop}unused",
                "USER"
        ));
        String userToken = jwtService.generateToken(user);

        for (MockHttpServletRequestBuilder request : writeRequests()) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }

        for (MockHttpServletRequestBuilder request : writeRequests()) {
            mvc.perform(request.header("Authorization", bearer(userToken)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void createRecordsPricesWithVariantsAndBenchmarksInheritingTheirDirection() throws Exception {
        String model = "Priced Phone " + UUID.randomUUID();
        String latency = "Test Latency " + UUID.randomUUID();
        long other = jdbc.queryForObject(
                "INSERT INTO products (brand, model_name, category) VALUES ('Other', ?, 'SMARTPHONE') RETURNING id",
                Long.class, "Other " + UUID.randomUUID());
        jdbc.update("""
                INSERT INTO benchmark_results (product_id, benchmark_name, score, unit, higher_is_better, observed_at)
                VALUES (?, ?, 12, 'ms', FALSE, '2026-01-01T00:00:00Z')
                """, other, latency);

        String body = """
                {"brand":"Test Brand","modelName":"%s","status":"VERIFIED",
                 "prices":[
                   {"observedOn":"2026-01-01","price":999.00,"currency":"SGD","source":" "},
                   {"observedOn":"2026-03-01","price":1099,"currency":"SGD","storageGb":256,"ramGb":8,"source":"Shopee"},
                   {"observedOn":"2026-02-01","price":1049,"currency":"SGD","storageGb":256,"ramGb":8}
                 ],
                 "benchmarks":[
                   {"benchmarkName":"%s","score":9,"observedOn":"2026-02-01"},
                   {"benchmarkName":" Brand New Bench ","score":1500,"observedOn":"2026-02-01","source":"Lab"}
                 ]}
                """.formatted(model, latency.toUpperCase());

        mvc.perform(MockMvcRequestBuilders.post("/api/admin/catalogue/smartphones")
                        .header("Authorization", bearer(adminToken))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.latestPrice").value(1099.0))
                .andExpect(jsonPath("$.currency").value("SGD"));

        long id = products.findByBrandAndModelName("Test Brand", model).orElseThrow().getId();
        // Both 256 GB / 8 GB prices share one variant row; the blank-source price is model-level.
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM phone_variants WHERE product_id = ?", Integer.class, id));
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM price_history WHERE product_id = ? AND phone_variant_id IS NOT NULL",
                Integer.class, id));
        assertEquals("admin", jdbc.queryForObject(
                "SELECT source FROM price_history WHERE product_id = ? AND phone_variant_id IS NULL",
                String.class, id));

        // A known name adopts the stored spelling, unit and direction; a new one is higher-is-better.
        var known = jdbc.queryForMap(
                "SELECT benchmark_name, unit, higher_is_better, source FROM benchmark_results "
                        + "WHERE product_id = ? AND score = 9", id);
        assertEquals(latency, known.get("benchmark_name"));
        assertEquals("ms", known.get("unit"));
        assertEquals(false, known.get("higher_is_better"));
        assertEquals("admin", known.get("source"));
        var fresh = jdbc.queryForMap(
                "SELECT benchmark_name, unit, higher_is_better, source FROM benchmark_results "
                        + "WHERE product_id = ? AND score = 1500", id);
        assertEquals("Brand New Bench", fresh.get("benchmark_name"));
        assertNull(fresh.get("unit"));
        assertEquals(true, fresh.get("higher_is_better"));
        assertEquals("Lab", fresh.get("source"));
    }

    @Test
    void aFutureObservationRejectsTheWholeCreate() throws Exception {
        String model = "Future Phone " + UUID.randomUUID();
        String body = """
                {"brand":"Test Brand","modelName":"%s","status":"VERIFIED",
                 "prices":[{"observedOn":"%s","price":999,"currency":"SGD"}]}
                """.formatted(model, java.time.LocalDate.now().plusDays(5));

        mvc.perform(MockMvcRequestBuilders.post("/api/admin/catalogue/smartphones")
                        .header("Authorization", bearer(adminToken))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest());

        assertFalse(products.findByBrandAndModelName("Test Brand", model).isPresent());
    }

    @Test
    void anInvalidObservationIsRejected() throws Exception {
        String body = """
                {"brand":"Test Brand","modelName":"Bad %s","status":"VERIFIED",
                 "prices":[{"observedOn":"2026-01-01","price":-1,"currency":"sgd"}],
                 "benchmarks":[{"benchmarkName":"","score":5,"observedOn":"2026-01-01"}]}
                """.formatted(UUID.randomUUID());

        mvc.perform(MockMvcRequestBuilders.post("/api/admin/catalogue/smartphones")
                        .header("Authorization", bearer(adminToken))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateDoesNotAcceptObservations() throws Exception {
        String model = "Edit Phone " + UUID.randomUUID();
        mvc.perform(MockMvcRequestBuilders.post("/api/admin/catalogue/smartphones")
                        .header("Authorization", bearer(adminToken))
                        .contentType("application/json")
                        .content(phoneJson("Test Brand", model, "Test Chip", 8, 256)))
                .andExpect(status().isCreated());
        long id = products.findByBrandAndModelName("Test Brand", model).orElseThrow().getId();

        mvc.perform(MockMvcRequestBuilders.put("/api/admin/catalogue/smartphones/{id}", id)
                        .header("Authorization", bearer(adminToken))
                        .contentType("application/json")
                        .content("""
                                {"brand":"Test Brand","modelName":"%s","status":"VERIFIED",
                                 "prices":[{"observedOn":"2026-01-01","price":999,"currency":"SGD"}]}
                                """.formatted(model)))
                .andExpect(status().isBadRequest());
    }

    private String phoneJson(String brand, String model, String chipset, int ramGb, int storageGb) {
        return """
                {
                  "brand": "%s",
                  "modelName": "%s",
                  "releaseDate": "2026-09-01",
                  "status": "VERIFIED",
                  "chipset": "%s",
                  "ramGb": %d,
                  "storageGb": %d,
                  "batteryMah": 5000
                }
                """.formatted(brand, model, chipset, ramGb, storageGb);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private MockHttpServletRequestBuilder[] writeRequests() {
        String body = phoneJson("Test Brand", "Protected Phone", "Test Chip", 8, 256);
        return new MockHttpServletRequestBuilder[] {
                MockMvcRequestBuilders.post("/api/admin/catalogue/smartphones")
                        .contentType("application/json").content(body),
                MockMvcRequestBuilders.put("/api/admin/catalogue/smartphones/1")
                        .contentType("application/json").content(body),
                MockMvcRequestBuilders.delete("/api/admin/catalogue/smartphones/1")
        };
    }
}
