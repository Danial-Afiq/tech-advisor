package com.springboot.backend.ingestion;

import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/** Scoped to this feature so the account/auth ticket can replace its integration boundary. */
@Configuration
public class IngestionSecurity {
    @Bean @Profile("!ingestion-demo")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(UserDetailsService.class)
    UserDetailsService noDemoUsers() { return new InMemoryUserDetailsManager(); }
    @Bean @Order(1) @Profile("!ingestion-demo")
    SecurityFilterChain closedIngestion(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api/admin/ingestion/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().denyAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((req, res, ex) -> res.sendError(401)))
                .build();
    }
    @Bean @Order(1) @Profile("ingestion-demo")
    SecurityFilterChain demoIngestion(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api/admin/ingestion/**")
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ADMIN"))
                .httpBasic(Customizer.withDefaults()).build(); // CSRF stays enabled, including for Basic auth.
    }
    @Bean @Profile("ingestion-demo")
    UserDetailsService demoUsers(@Value("${ingestion.demo-password}") String password) {
        if (password.length() < 12) throw new IllegalArgumentException("Demo password must contain at least 12 characters");
        return new InMemoryUserDetailsManager(User.withUsername("demo-admin")
                .password("{bcrypt}" + new BCryptPasswordEncoder().encode(password)).roles("ADMIN").build());
    }
}
