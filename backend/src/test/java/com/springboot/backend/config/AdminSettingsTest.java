package com.springboot.backend.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminSettingsTest {

    @Test
    void acceptsValidConfiguration() {
        AdminSettings settings = new AdminSettings(
                "admin@example.com",
                "long-enough-password"
        );

        assertEquals("admin@example.com", settings.email());
        assertEquals("long-enough-password", settings.password());
    }

    @Test
    void rejectsMissingOrBlankEmail() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AdminSettings(
                        null,
                        "long-enough-password"
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new AdminSettings(
                        "   ",
                        "long-enough-password"
                )
        );
    }

    @Test
    void rejectsMissingBlankOrShortPassword() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AdminSettings(
                        "admin@example.com",
                        null
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new AdminSettings(
                        "admin@example.com",
                        "   "
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new AdminSettings(
                        "admin@example.com",
                        "short"
                )
        );
    }
}
