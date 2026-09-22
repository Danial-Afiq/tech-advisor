package com.springboot.backend;

import com.springboot.backend.model.User;
import com.springboot.backend.repository.UserRepository;
import com.springboot.backend.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "ingestion.reconciliation-enabled=false")
class ProfileAuthenticationIntegrationTest {

    @Autowired
    WebApplicationContext web;

    @Autowired
    UserRepository users;

    @Autowired
    JwtService jwtService;

    @Test
    void profileRequiresTokenAndReturnsItsOwnersProfile() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(web)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        String email = "profile-" + UUID.randomUUID() + "@example.com";
        User user = users.save(new User(email, "hashed", "USER"));

        try {
            mvc.perform(MockMvcRequestBuilders.get("/api/profile"))
                    .andExpect(status().isUnauthorized());

            mvc.perform(MockMvcRequestBuilders.get("/api/profile")
                            .header("Authorization", "Bearer invalid"))
                    .andExpect(status().isUnauthorized());

            mvc.perform(MockMvcRequestBuilders.get("/api/profile")
                            .header("Authorization",
                                    "Bearer " + jwtService.generateToken(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value(email))
                    .andExpect(jsonPath("$.role").value("USER"));
        } finally {
            users.delete(user);
        }
    }
}