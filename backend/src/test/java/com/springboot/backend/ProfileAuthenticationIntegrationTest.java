package com.springboot.backend;

import com.springboot.backend.model.User;
import com.springboot.backend.repository.UserRepository;
import com.springboot.backend.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "ingestion.reconciliation-enabled=false")
class ProfileAuthenticationIntegrationTest {

    @Autowired
    private WebApplicationContext web;

    @Autowired
    private UserRepository users;

    @Autowired
    private JwtService jwtService;

    @Test
    void profileRequiresTokenAndReturnsItsOwnersProfile() throws Exception {
        MockMvc mvc = mvc();
        String email = "profile-" + UUID.randomUUID() + "@example.com";
        User user = users.save(new User(email, "hashed", "USER"));

        try {
            mvc.perform(get("/api/profile"))
                    .andExpect(status().isUnauthorized());

            mvc.perform(get("/api/profile")
                            .header("Authorization", "Bearer invalid"))
                    .andExpect(status().isUnauthorized());

            mvc.perform(get("/api/profile")
                            .header(
                                    "Authorization",
                                    "Bearer " + jwtService.generateToken(user)
                            ))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value(email))
                    .andExpect(jsonPath("$.role").value("USER"))
                    .andExpect(jsonPath("$.recommendationEmailsEnabled").value(true));
        } finally {
            users.delete(user);
        }
    }

    @Test
    void userCanDisableAndReenableRecommendationEmails() throws Exception {
        MockMvc mvc = mvc();
        String email = "preference-" + UUID.randomUUID() + "@example.com";
        User user = users.save(new User(email, "hashed", "USER"));

        try {
            String token = jwtService.generateToken(user);

            mvc.perform(put("/api/profile/email-notifications")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "enabled": false
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value(email))
                    .andExpect(jsonPath("$.recommendationEmailsEnabled").value(false));

            User disabled = users.findById(user.getId()).orElseThrow();
            assertThat(disabled.isRecommendationEmailsEnabled()).isFalse();

            mvc.perform(put("/api/profile/email-notifications")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "enabled": true
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.recommendationEmailsEnabled").value(true));

            User reenabled = users.findById(user.getId()).orElseThrow();
            assertThat(reenabled.isRecommendationEmailsEnabled()).isTrue();
        } finally {
            users.delete(user);
        }
    }

    @Test
    void updatingPreferenceRequiresAuthenticationAndAValue() throws Exception {
        MockMvc mvc = mvc();
        String email = "validation-" + UUID.randomUUID() + "@example.com";
        User user = users.save(new User(email, "hashed", "USER"));

        try {
            mvc.perform(put("/api/profile/email-notifications")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "enabled": false
                                    }
                                    """))
                    .andExpect(status().isUnauthorized());

            mvc.perform(put("/api/profile/email-notifications")
                            .header(
                                    "Authorization",
                                    "Bearer " + jwtService.generateToken(user)
                            )
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "enabled": null
                                    }
                                    """))
                    .andExpect(status().isBadRequest());

            assertThat(
                    users.findById(user.getId())
                            .orElseThrow()
                            .isRecommendationEmailsEnabled()
            ).isTrue();
        } finally {
            users.delete(user);
        }
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(web)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }
}