package com.springboot.backend;

import com.springboot.backend.model.User;
import com.springboot.backend.repository.UserRepository;
import com.springboot.backend.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "ingestion.reconciliation-enabled=false",
        "ingestion.scheduling-enabled=false",
        "logging.level.root=WARN"
})
@Transactional
class AdminDatabaseIntegrationTest {

    @Autowired WebApplicationContext web;
    @Autowired JwtService jwtService;
    @Autowired UserRepository users;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(web)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void adminSeesEveryTableWithColumnsKeysAndCounts() throws Exception {
        String token = jwtService.generateToken(save("ADMIN"));

        mvc.perform(MockMvcRequestBuilders.get("/api/admin/database/tables")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'users')]").exists())
                .andExpect(jsonPath("$[?(@.name == 'products')]").exists())
                .andExpect(jsonPath("$[?(@.name == 'users')].rowCount").value(hasItem(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[?(@.name == 'users')].columns[?(@.name == 'id')].primaryKey").value(true))
                .andExpect(jsonPath("$[?(@.name == 'phone')].columns[?(@.name == 'product_id')].references")
                        .value("products"));
    }

    @Test
    void userAndAnonymousAreRejected() throws Exception {
        String token = jwtService.generateToken(save("USER"));

        mvc.perform(MockMvcRequestBuilders.get("/api/admin/database/tables")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.get("/api/admin/database/tables"))
                .andExpect(status().isUnauthorized());
    }

    private User save(String role) {
        return users.save(new User("db-" + role.toLowerCase() + "-" + UUID.randomUUID() + "@example.test",
                "{noop}unused", role));
    }
}
