package com.springboot.backend;

import com.springboot.backend.model.Gpu;
import com.springboot.backend.model.Product;
import com.springboot.backend.model.User;
import com.springboot.backend.repository.GpuRepository;
import com.springboot.backend.repository.ProductRepository;
import com.springboot.backend.repository.UserRepository;
import com.springboot.backend.service.GpuCatalogueService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Same properties as SmartphoneCatalogueIntegrationTest, so the two share one cached context.
@SpringBootTest(properties = {
        "ingestion.reconciliation-enabled=false",
        "ingestion.scheduling-enabled=false",
        "logging.level.root=WARN"
})
@Transactional
class GpuCatalogueIntegrationTest {

    private static final String ADMIN_GPUS = "/api/admin/catalogue/gpus";

    @Autowired WebApplicationContext web;
    @Autowired JwtService jwtService;
    @Autowired UserRepository users;
    @Autowired ProductRepository products;
    @Autowired GpuRepository gpus;
    @Autowired JdbcTemplate jdbc;

    private MockMvc mvc;
    private String adminToken;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(web)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        adminToken = token("ADMIN");
    }

    @Test
    void adminCanCreateReadUpdateAndDeleteAGpu() throws Exception {
        String model = "Test GPU " + UUID.randomUUID();

        mvc.perform(MockMvcRequestBuilders.post(ADMIN_GPUS)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(gpuJson("Test Brand", model, 1500, "12", "GDDR6")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.brand").value("Test Brand"))
                .andExpect(jsonPath("$.modelName").value(model))
                .andExpect(jsonPath("$.baseClockMhz").value(1500))
                .andExpect(jsonPath("$.vramGb").value(12))
                .andExpect(jsonPath("$.memoryType").value("GDDR6"))
                .andExpect(jsonPath("$.source").value(GpuCatalogueService.ADMIN_SOURCE));

        Product product = products.findByBrandAndModelName("Test Brand", model).orElseThrow();
        assertEquals(Product.CATEGORY_GPU, product.getCategory());
        assertTrue(gpus.findById(product.getId()).isPresent());

        mvc.perform(MockMvcRequestBuilders.get(ADMIN_GPUS + "/{id}", product.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(product.getId()))
                .andExpect(jsonPath("$.latestPrice").value(nullValue()));

        mvc.perform(MockMvcRequestBuilders.get(ADMIN_GPUS)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %s)]", product.getId()).exists());

        // A blank text spec and an omitted number are both stored as unknown, never 0 or "".
        mvc.perform(MockMvcRequestBuilders.put(ADMIN_GPUS + "/{id}", product.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(gpuJson("Updated Brand", model, null, "16", " ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brand").value("Updated Brand"))
                .andExpect(jsonPath("$.baseClockMhz").value(nullValue()))
                .andExpect(jsonPath("$.vramGb").value(16))
                .andExpect(jsonPath("$.memoryType").value(nullValue()));

        Gpu saved = gpus.findById(product.getId()).orElseThrow();
        assertNull(saved.getBaseClockMhz());
        assertNull(saved.getMemoryType());

        mvc.perform(MockMvcRequestBuilders.delete(ADMIN_GPUS + "/{id}", product.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        assertFalse(products.existsById(product.getId()));
        assertFalse(gpus.existsById(product.getId()));
    }

    @Test
    void duplicateBrandAndModelIsRejected() throws Exception {
        String model = "Duplicate GPU " + UUID.randomUUID();
        String body = gpuJson("Test Brand", model, 1500, "8", "GDDR6");
        mvc.perform(MockMvcRequestBuilders.post(ADMIN_GPUS)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated());

        mvc.perform(MockMvcRequestBuilders.post(ADMIN_GPUS)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(gpuJson("test brand", model.toUpperCase(), 1500, "8", "GDDR6")))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidInputDoesNotCreateEitherRow() throws Exception {
        String model = "Invalid GPU " + UUID.randomUUID();
        long productCount = products.count();
        long gpuCount = gpus.count();

        mvc.perform(MockMvcRequestBuilders.post(ADMIN_GPUS)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(gpuJson("Test Brand", model, -1, "8", "GDDR6")))
                .andExpect(status().isBadRequest());

        assertTrue(products.findByBrandAndModelName("Test Brand", model).isEmpty());
        assertEquals(productCount, products.count());
        assertEquals(gpuCount, gpus.count());
    }

    @Test
    void aSmartphoneIdIsNotAGpu() throws Exception {
        Product phone = products.save(new Product(
                "Test Brand", "Not A GPU " + UUID.randomUUID(), Product.CATEGORY_SMARTPHONE, "VERIFIED"));

        mvc.perform(MockMvcRequestBuilders.get(ADMIN_GPUS + "/{id}", phone.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyAdminsCanUseTheGpuCatalogue() throws Exception {
        String userToken = token("USER");

        mvc.perform(MockMvcRequestBuilders.get(ADMIN_GPUS))
                .andExpect(status().isUnauthorized());
        mvc.perform(MockMvcRequestBuilders.get(ADMIN_GPUS)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.post(ADMIN_GPUS)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType("application/json")
                        .content(gpuJson("Test Brand", "User GPU " + UUID.randomUUID(), 1500, "8", "GDDR6")))
                .andExpect(status().isForbidden());
    }

    @Test
    void signedInUsersCanReadGpusButNotChangeThem() throws Exception {
        String userToken = token("USER");
        Product product = products.save(new Product(
                "Test Brand", "Readable GPU " + UUID.randomUUID(), Product.CATEGORY_GPU, "VERIFIED"));
        Gpu gpu = new Gpu(product.getId());
        gpu.setVramGb(new java.math.BigDecimal("8"));
        gpus.save(gpu);

        mvc.perform(MockMvcRequestBuilders.get("/api/catalogue/gpus"))
                .andExpect(status().isUnauthorized());
        mvc.perform(MockMvcRequestBuilders.get("/api/catalogue/gpus")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %s)].vramGb", product.getId()).value(8));
        mvc.perform(MockMvcRequestBuilders.get("/api/catalogue/gpus/{id}", product.getId())
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelName").value(product.getModelName()))
                .andExpect(jsonPath("$.imageUrl").value(nullValue()));
        mvc.perform(MockMvcRequestBuilders.delete(ADMIN_GPUS + "/{id}", product.getId())
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void createRecordsModelLevelPricesAndUpdateRejectsThem() throws Exception {
        String model = "Priced GPU " + UUID.randomUUID();
        String body = """
                {"brand":"Test Brand","modelName":"%s","status":"VERIFIED",
                 "prices":[
                   {"observedOn":"2026-01-01","price":549,"currency":"USD","source":"Newegg"},
                   {"observedOn":"2026-02-01","price":499.90,"currency":"USD"}
                 ]}
                """.formatted(model);

        mvc.perform(MockMvcRequestBuilders.post(ADMIN_GPUS)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.latestPrice").value(499.9))
                .andExpect(jsonPath("$.currency").value("USD"));

        long id = products.findByBrandAndModelName("Test Brand", model).orElseThrow().getId();
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM price_history WHERE product_id = ? AND phone_variant_id IS NULL",
                Integer.class, id));
        assertEquals("admin", jdbc.queryForObject(
                "SELECT source FROM price_history WHERE product_id = ? AND price = 499.90", String.class, id));

        mvc.perform(MockMvcRequestBuilders.put(ADMIN_GPUS + "/{id}", id)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
    }

    private String token(String role) {
        User user = users.save(new User(
                "gpu-catalogue-" + role.toLowerCase() + "-" + UUID.randomUUID() + "@example.test",
                "{noop}unused",
                role));
        return jwtService.generateToken(user);
    }

    private static String gpuJson(String brand, String model, Integer baseClockMhz, String vramGb, String memoryType) {
        return """
                {"brand":"%s","modelName":"%s","releaseDate":"2024-01-15","status":"VERIFIED",
                 "baseClockMhz":%s,"boostClockMhz":2500,"vramGb":%s,"memoryType":"%s",
                 "memoryBusBits":192,"tdpWatts":200,"architecture":"Test Arch"}
                """.formatted(brand, model, baseClockMhz, vramGb, memoryType);
    }
}
