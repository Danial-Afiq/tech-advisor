package com.springboot.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import com.springboot.backend.service.JwtService;
import com.springboot.backend.model.User;
import com.springboot.backend.repository.UserRepository;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "ingestion.reconciliation-enabled=false",
        "admin.email=test-admin@example.com",
        "admin.password=test-admin-password"
})
class AdminAuthorizationIntegrationTest {

    @Autowired
    WebApplicationContext web;

    @Autowired
    JwtService jwtService;

    @Autowired
    UserRepository userRepository;

    @DynamicPropertySource
    static void jwtConfiguration(
            DynamicPropertyRegistry registry) {

        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);

        registry.add(
                "jwt.secret",
                () -> Base64.getEncoder()
                        .encodeToString(secret)
        );

        registry.add(
                "jwt.expiration-seconds",
                () -> 3600L
        );
    }

    @Test
    void validAdminCredentialsReturnToken()
            throws Exception {

        var mvc = MockMvcBuilders
                .webAppContextSetup(web)
                .apply(
                        SecurityMockMvcConfigurers
                                .springSecurity()
                )
                .build();

        mvc.perform(
                        MockMvcRequestBuilders
                                .post("/api/auth/admin/login")
                                .contentType("application/json")
                                .content("""
                                        {
                                            "email": "test-admin@example.com",
                                            "password": "test-admin-password"
                                        }
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.token").isNotEmpty()
                )
                .andExpect(
                        jsonPath("$.tokenType")
                                .value("Bearer")
                )
                .andExpect(
                        jsonPath("$.expiresIn")
                                .value(3600)
                );
    }

    @Test
    void invalidAdminCredentialsReturnUnauthorized()
            throws Exception {

        var mvc = MockMvcBuilders
                .webAppContextSetup(web)
                .apply(
                        SecurityMockMvcConfigurers
                                .springSecurity()
                )
                .build();

        mvc.perform(
                        MockMvcRequestBuilders
                                .post("/api/auth/admin/login")
                                .contentType("application/json")
                                .content("""
                                        {
                                        "email": "not-the-admin@example.com",
                                        "password": "wrong-password"
                                        }
                                        """)
                )
                .andExpect(status().isUnauthorized())
                .andExpect(
                        jsonPath("$.error")
                                .value(
                                        "Invalid email or password"
                                )
                );
    }

    @Test
    void adminTokenCanAccessAdminEndpoint()
            throws Exception {

        var mvc = MockMvcBuilders
                .webAppContextSetup(web)
                .apply(
                        SecurityMockMvcConfigurers
                                .springSecurity()
                )
                .build();

        User admin = userRepository
                .findByEmailIgnoreCase(
                        "test-admin@example.com"
                )
                .orElseThrow();

        String adminToken =
                jwtService.generateToken(admin);

        mvc.perform(
                        MockMvcRequestBuilders
                                .get("/api/admin/ingestion/runs")
                                .header(
                                        "Authorization",
                                        "Bearer " + adminToken
                                )
                )
                .andExpect(status().isOk());
    }

    @Test
    void userTokenCannotAccessAdminEndpoint()
            throws Exception {

        var mvc = MockMvcBuilders
                .webAppContextSetup(web)
                .apply(
                        SecurityMockMvcConfigurers
                                .springSecurity()
                )
                .build();

        String email =
                "ordinary-user-"
                        + UUID.randomUUID()
                        + "@example.com";

        User user = userRepository.save(
                new User(
                        email,
                        "unused-test-password-hash",
                        "USER"
                )
        );

        try {
            String userToken =
                    jwtService.generateToken(user);

            mvc.perform(
                            MockMvcRequestBuilders
                                    .get(
                                            "/api/admin/ingestion/runs"
                                    )
                                    .header(
                                            "Authorization",
                                            "Bearer " + userToken
                                    )
                    )
                    .andExpect(
                            status().isForbidden()
                    );
        } finally {
            userRepository.delete(user);
        }
    }

    @Test
    void missingOrInvalidTokenCannotAccessAdminEndpoint()
            throws Exception {

        var mvc = MockMvcBuilders
                .webAppContextSetup(web)
                .apply(
                        SecurityMockMvcConfigurers
                                .springSecurity()
                )
                .build();

        mvc.perform(
                        MockMvcRequestBuilders
                                .get(
                                        "/api/admin/ingestion/runs"
                                )
                )
                .andExpect(
                        status().isUnauthorized()
                );

        mvc.perform(
                        MockMvcRequestBuilders
                                .get(
                                        "/api/admin/ingestion/runs"
                                )
                                .header(
                                        "Authorization",
                                        "Bearer invalid-token"
                                )
                )
                .andExpect(
                        status().isUnauthorized()
                );
    }
}