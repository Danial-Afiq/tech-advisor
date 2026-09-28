package com.springboot.backend.ingestion.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@Profile("ingestion-demo")
public class IngestionSecurity {

    @Bean
    @Order(1)
    SecurityFilterChain demoIngestion(
            HttpSecurity http) throws Exception {

        return http
                .securityMatcher("/api/admin/ingestion/**")
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth ->
                        auth.anyRequest().hasRole("ADMIN")
                )
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    UserDetailsService demoUsers(
            @Value("${ingestion.demo-password}")
            String password) {

        if (password.length() < 12) {
            throw new IllegalArgumentException(
                    "Demo password must contain at least 12 characters"
            );
        }

        return new InMemoryUserDetailsManager(
                User.withUsername("demo-admin")
                        .password(
                                "{bcrypt}"
                                        + new BCryptPasswordEncoder()
                                                .encode(password)
                        )
                        .roles("ADMIN")
                        .build()
        );
    }
}