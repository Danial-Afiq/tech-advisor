package com.springboot.backend.service;

import com.springboot.backend.model.User;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private static final String TEST_SECRET =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private JwtService jwtService;
    private User user;

    @BeforeEach
    void setUp() {

        jwtService = new JwtService(
                TEST_SECRET,
                3600L
        );

        user = new User(
                "test@example.com",
                "password-hash",
                "USER"
        );
    }

    @Test
    void generatedTokenShouldContainCorrectEmail() {

        String token = jwtService.generateToken(user);

        String email = jwtService.extractEmail(token);

        assertEquals("test@example.com", email);
    }

    @Test
    void generatedTokenShouldBeValid() {

        String token = jwtService.generateToken(user);

        boolean valid = jwtService.isTokenValid(
                token,
                "test@example.com"
        );

        assertTrue(valid);
    }

    @Test
    void alteredTokenShouldBeRejected() {

        String token = jwtService.generateToken(user);
        String alteredToken = token + "x";

        assertThrows(
                JwtException.class,
                () -> jwtService.extractEmail(alteredToken)
        );
    }

    @Test
    void expiredTokenShouldBeRejected() {

        JwtService expiredJwtService = new JwtService(
                TEST_SECRET,
                -1L
        );

        String expiredToken =
                expiredJwtService.generateToken(user);

        assertThrows(
                JwtException.class,
                () -> expiredJwtService.extractEmail(expiredToken)
        );
    }
}