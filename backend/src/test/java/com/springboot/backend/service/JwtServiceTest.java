package com.springboot.backend.service;

import com.springboot.backend.model.User;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private static final String TEST_SECRET = testSecret(32);

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
    void validBase64SecretOfAtLeast32BytesShouldBeAccepted() {
        assertDoesNotThrow(() -> new JwtService(testSecret(32), 3600L));
        assertDoesNotThrow(() -> new JwtService(testSecret(64), 3600L));
    }

    @Test
    void missingSecretShouldBeRejectedAtStartup() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JwtService(null, 3600L)
        );

        assertTrue(exception.getMessage().contains("JWT_SECRET"));
    }

    @Test
    void blankSecretShouldBeRejectedAtStartup() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JwtService("   ", 3600L)
        );

        assertTrue(exception.getMessage().contains("JWT_SECRET"));
    }

    @Test
    void invalidBase64SecretShouldBeRejectedAtStartup() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JwtService("not-valid-base64%%%", 3600L)
        );

        assertTrue(exception.getMessage().contains("valid Base64"));
    }

    @Test
    void secretShorterThan32DecodedBytesShouldBeRejectedAtStartup() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new JwtService(testSecret(31), 3600L)
        );

        assertTrue(exception.getMessage().contains("at least 32 bytes"));
    }

    @Test
    void nonPositiveExpirationShouldBeRejectedAtStartup() {
        IllegalArgumentException zeroException = assertThrows(
                IllegalArgumentException.class,
                () -> new JwtService(TEST_SECRET, 0L)
        );
        IllegalArgumentException negativeException = assertThrows(
                IllegalArgumentException.class,
                () -> new JwtService(TEST_SECRET, -1L)
        );

        assertTrue(zeroException.getMessage().contains("JWT_EXPIRATION_SECONDS"));
        assertTrue(negativeException.getMessage().contains("JWT_EXPIRATION_SECONDS"));
    }

    private static String testSecret(int decodedBytes) {
        byte[] secret = new byte[decodedBytes];
        new SecureRandom().nextBytes(secret);
        return Base64.getEncoder().encodeToString(secret);
    }
}
