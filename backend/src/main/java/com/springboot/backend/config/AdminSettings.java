package com.springboot.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("admin")
public record AdminSettings(
        String email,
        String password) {

    public AdminSettings {

        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException(
                    "ADMIN_EMAIL is required"
            );
        }

        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException(
                    "ADMIN_PASSWORD is required"
            );
        }

        if (password.length() < 12) {
            throw new IllegalArgumentException(
                    "ADMIN_PASSWORD must contain at least 12 characters"
            );
        }
    }
}