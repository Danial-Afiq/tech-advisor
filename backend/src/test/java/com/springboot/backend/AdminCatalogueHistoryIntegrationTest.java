package com.springboot.backend;

import com.springboot.backend.model.User;
import com.springboot.backend.repository.UserRepository;
import com.springboot.backend.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "ingestion.reconciliation-enabled=false",
        "ingestion.scheduling-enabled=false",
        "logging.level.root=WARN"
})
@Transactional
class AdminCatalogueHistoryIntegrationTest {

    @Autowired WebApplicationContext web;
    @Autowired JwtService jwtService;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    private MockMvc mvc;
    private String adminToken;
    private long productId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(web)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        adminToken = jwtService.generateToken(save("ADMIN"));
        productId = jdbc.queryForObject(
                "INSERT INTO products (brand, model_name, category) VALUES ('History Brand', ?, 'SMARTPHONE') RETURNING id",
                Long.class, "History Phone " + UUID.randomUUID());
    }

    @Test
    void pricesAreNewestFirstWithTheirVariant() throws Exception {
        long variantId = jdbc.queryForObject(
                "INSERT INTO phone_variants (product_id, storage_gb, ram_gb) VALUES (?, 256, 8) RETURNING id",
                Long.class, productId);
        String insert = """
                INSERT INTO price_history (product_id, price, currency, source, observed_at, phone_variant_id)
                VALUES (?, ?, 'SGD', ?, ?::timestamptz, ?)
                """;
        jdbc.update(insert, productId, 999.00, "mobileapi", "2026-01-01T00:00:00Z", null);
        jdbc.update(insert, productId, 1049.00, "searchapi", "2026-03-01T00:00:00Z", variantId);

        mvc.perform(MockMvcRequestBuilders.get("/api/admin/catalogue/smartphones/{id}/prices", productId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].price").value(1049.0))
                .andExpect(jsonPath("$[0].source").value("searchapi"))
                .andExpect(jsonPath("$[0].variant").value("256 GB · 8 GB RAM"))
                .andExpect(jsonPath("$[0].observedAt").exists())
                .andExpect(jsonPath("$[1].price").value(999.0))
                .andExpect(jsonPath("$[1].variant").value(nullValue()));
    }

    @Test
    void benchmarksAreNewestFirst() throws Exception {
        String insert = """
                INSERT INTO benchmark_results (product_id, benchmark_name, score, unit, higher_is_better, source, observed_at)
                VALUES (?, ?, ?, ?, ?, 'manual', ?::timestamptz)
                """;
        jdbc.update(insert, productId, "Geekbench 6 multi-core", 6800, "points", true, "2026-01-01T00:00:00Z");
        jdbc.update(insert, productId, "Battery life", 14.5, "hours", true, "2026-02-01T00:00:00Z");

        mvc.perform(MockMvcRequestBuilders.get("/api/admin/catalogue/smartphones/{id}/benchmarks", productId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].benchmarkName").value("Battery life"))
                .andExpect(jsonPath("$[0].score").value(14.5))
                .andExpect(jsonPath("$[0].unit").value("hours"))
                .andExpect(jsonPath("$[0].higherIsBetter").value(true))
                .andExpect(jsonPath("$[1].benchmarkName").value("Geekbench 6 multi-core"));
    }

    @Test
    void emptyHistoryUnknownProductAndNonAdmins() throws Exception {
        for (String path : new String[] {"prices", "benchmarks"}) {
            String url = "/api/admin/catalogue/smartphones/{id}/" + path;
            mvc.perform(MockMvcRequestBuilders.get(url, productId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
            mvc.perform(MockMvcRequestBuilders.get(url, Long.MAX_VALUE)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isNotFound());
            mvc.perform(MockMvcRequestBuilders.get(url, productId)
                            .header("Authorization", "Bearer " + jwtService.generateToken(save("USER"))))
                    .andExpect(status().isForbidden());
            mvc.perform(MockMvcRequestBuilders.get(url, productId))
                    .andExpect(status().isUnauthorized());
        }
    }

    private User save(String role) {
        return users.save(new User("history-" + role.toLowerCase() + "-" + UUID.randomUUID() + "@example.test",
                "{noop}unused", role));
    }
}
