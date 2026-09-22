package com.springboot.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.SecureRandom;
import java.util.Base64;

@SpringBootTest(properties = {"ingestion.reconciliation-enabled=false", "logging.level.root=WARN", "debug=false"})
class BackendApplicationTests {

    @DynamicPropertySource
    static void validJwtTestConfiguration(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        registry.add("jwt.secret", () -> Base64.getEncoder().encodeToString(secret));
        registry.add("jwt.expiration-seconds", () -> 3600L);
    }

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.web.context.WebApplicationContext web;

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.JdbcTemplate db;

    @Test
    void pgvectorMigrationMakesVectorOperationsAvailable() {
        org.junit.jupiter.api.Assertions.assertNotNull(db.queryForObject(
                "SELECT extversion FROM pg_extension WHERE extname='vector'", String.class));
        org.junit.jupiter.api.Assertions.assertEquals(1.0, db.queryForObject(
                "SELECT '[1,2,3]'::vector <-> '[1,2,4]'::vector", Double.class));
    }

    @Test
    void productionIngestionIsDeniedUntilAccountAuthIsIntegrated() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/ingestion/runs")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/health"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

	@Test
	void contextLoadsWithValidJwtConfiguration() {
	}

}
