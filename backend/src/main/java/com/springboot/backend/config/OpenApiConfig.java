package com.springboot.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String BEARER_AUTH = "bearerAuth";
    public static final String DEMO_BASIC_AUTH = "demoBasicAuth";

    @Bean
    public OpenAPI techAdvisorOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Tech Advisor API")
                        .version("v1")
                        .description("REST API for Tech Advisor authentication, profiles, owned devices, and admin ingestion operations.")
                        .contact(new Contact()
                                .name("Tech Advisor team")
                                .url("https://github.com/Danial-Afiq/tech-advisor")))
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                                .name(BEARER_AUTH)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT returned by POST /api/auth/login."))
                        .addSecuritySchemes(DEMO_BASIC_AUTH, new SecurityScheme()
                                .name(DEMO_BASIC_AUTH)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("basic")
                                .description("Local ingestion-demo profile only. Production ingestion routes remain disabled.")));
    }
}
