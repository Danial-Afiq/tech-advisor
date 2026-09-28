package com.springboot.backend;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {
        "ingestion.reconciliation-enabled=false",
        "logging.level.root=WARN",
        "debug=false"
})
class OpenApiDocumentationTests {

    @DynamicPropertySource
    static void validJwtTestConfiguration(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        registry.add("jwt.secret", () -> Base64.getEncoder().encodeToString(secret));
        registry.add("jwt.expiration-seconds", () -> 3600L);
    }

    @Autowired
    private WebApplicationContext web;

    private MockMvc mvc;

    @BeforeEach
    void configureMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(web)
                .apply(springSecurity())
                .build();
    }

    @Test
    void swaggerUiAndOpenApiJsonAreAccessibleWithoutAuthentication() throws Exception {
        mvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());

        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Swagger UI")));

        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
    }

    @Test
    void openApiContractListsRoutesMethodsSchemasSecurityAndStatuses() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Tech Advisor API"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.demoBasicAuth.scheme").value("basic"))

                .andExpect(jsonPath("$['paths']['/api/auth/register']['post']").exists())
                .andExpect(jsonPath("$['paths']['/api/auth/register']['post']['requestBody']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/RegisterRequest"))
                .andExpect(jsonPath("$['paths']['/api/auth/register']['post']['responses']['201']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/UserResponse"))
                .andExpect(jsonPath("$['paths']['/api/auth/register']['post']['responses']['400']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/ValidationErrorResponse"))
                .andExpect(jsonPath("$['paths']['/api/auth/register']['post']['responses']['409']").exists())

                .andExpect(jsonPath("$['paths']['/api/auth/login']['post']").exists())
                .andExpect(jsonPath("$['paths']['/api/auth/login']['post']['responses']['200']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/LoginResponse"))
                .andExpect(jsonPath("$['paths']['/api/auth/login']['post']['responses']['400']").exists())
                .andExpect(jsonPath("$['paths']['/api/auth/login']['post']['responses']['401']").exists())

                .andExpect(jsonPath("$['paths']['/api/profile']['get']").exists())
                .andExpect(jsonPath("$['paths']['/api/profile']['get']['security'][0]['bearerAuth']").isArray())
                .andExpect(jsonPath("$['paths']['/api/profile']['get']['responses']['200']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/UserResponse"))
                .andExpect(jsonPath("$['paths']['/api/profile']['get']['responses']['401']").exists())

                .andExpect(jsonPath("$['paths']['/api/devices']['post']").exists())
                .andExpect(jsonPath("$['paths']['/api/devices']['get']").exists())
                .andExpect(jsonPath("$['paths']['/api/devices']['post']['security'][0]['bearerAuth']").isArray())
                .andExpect(jsonPath("$['paths']['/api/devices']['post']['requestBody']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/DeviceRequest"))
                .andExpect(jsonPath("$['paths']['/api/devices']['post']['responses']['201']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/DeviceResponse"))
                .andExpect(jsonPath("$['paths']['/api/devices']['post']['responses']['400']").exists())
                .andExpect(jsonPath("$['paths']['/api/devices']['post']['responses']['400']['content']['application/json']['schema']['oneOf']").isArray())
                .andExpect(jsonPath("$['paths']['/api/devices']['post']['responses']['401']").exists())
                .andExpect(jsonPath("$['paths']['/api/devices']['post']['responses']['404']").exists())
                .andExpect(jsonPath("$['paths']['/api/devices']['get']['responses']['200']['content']['application/json']['schema']['items']['$ref']")
                        .value("#/components/schemas/DeviceResponse"))

                .andExpect(jsonPath("$['paths']['/api/devices/{deviceId}']['get']").exists())
                .andExpect(jsonPath("$['paths']['/api/devices/{deviceId}']['put']").exists())
                .andExpect(jsonPath("$['paths']['/api/devices/{deviceId}']['delete']").exists())
                .andExpect(jsonPath("$['paths']['/api/devices/{deviceId}']['get']['responses']['200']").exists())
                .andExpect(jsonPath("$['paths']['/api/devices/{deviceId}']['get']['responses']['401']").exists())
                .andExpect(jsonPath("$['paths']['/api/devices/{deviceId}']['get']['responses']['404']").exists())
                .andExpect(jsonPath("$['paths']['/api/devices/{deviceId}']['put']['responses']['200']").exists())
                .andExpect(jsonPath("$['paths']['/api/devices/{deviceId}']['put']['responses']['400']").exists())
                .andExpect(jsonPath("$['paths']['/api/devices/{deviceId}']['delete']['responses']['204']").exists())

                .andExpect(jsonPath("$['paths']['/api/admin/ingestion/session']['get']").exists())
                .andExpect(jsonPath("$['paths']['/api/admin/ingestion/session']['get']['responses']['200']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/IngestionSessionResponse"))
                .andExpect(jsonPath("$['paths']['/api/admin/ingestion/runs']['get']").exists())
                .andExpect(jsonPath("$['paths']['/api/admin/ingestion/runs']['post']").exists())
                .andExpect(jsonPath("$['paths']['/api/admin/ingestion/runs']['post']['security'][0]['demoBasicAuth']").isArray())
                .andExpect(jsonPath("$['paths']['/api/admin/ingestion/runs']['post']['responses']['202']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/RunLog"))
                .andExpect(jsonPath("$['paths']['/api/admin/ingestion/runs']['post']['responses']['400']").exists())
                .andExpect(jsonPath("$['paths']['/api/admin/ingestion/runs']['post']['responses']['401']").exists())
                .andExpect(jsonPath("$['paths']['/api/admin/ingestion/runs']['post']['responses']['403']").exists())
                .andExpect(jsonPath("$['paths']['/api/admin/ingestion/runs']['post']['responses']['409']").exists())
                .andExpect(jsonPath("$['paths']['/api/admin/ingestion/runs']['post']['responses']['503']").exists())
                .andExpect(jsonPath("$['paths']['/api/admin/ingestion/runs/{id}']['get']").exists())
                .andExpect(jsonPath("$['paths']['/api/admin/ingestion/schedule']['get']").exists())
                .andExpect(jsonPath("$['paths']['/api/admin/ingestion/schedule']['get']['responses']['200']['content']['application/json']['schema']['$ref']")
                        .value("#/components/schemas/IngestionScheduleResponse"))
                .andExpect(jsonPath("$['paths']['/api/admin/ingestion/sources']['get']").exists())
                .andExpect(jsonPath("$['paths']['/api/admin/ingestion/sources']['get']['responses']['200']['content']['application/json']['schema']['items']['$ref']")
                        .value("#/components/schemas/IngestionSourceResponse"))

                .andExpect(jsonPath("$.components.schemas.RegisterRequest.properties.email.format").value("email"))
                .andExpect(jsonPath("$.components.schemas.RegisterRequest.properties.password.minLength").value(8))
                .andExpect(jsonPath("$.components.schemas.DeviceRequest.properties.satisfactionScore.minimum").value(0))
                .andExpect(jsonPath("$.components.schemas.DeviceRequest.properties.satisfactionScore.maximum").value(100));
    }
}
