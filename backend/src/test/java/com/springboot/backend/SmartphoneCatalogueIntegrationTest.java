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
import static org.junit.jupiter.api.Assertions.assertTrue;
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
