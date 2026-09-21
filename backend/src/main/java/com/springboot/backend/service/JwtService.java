package com.springboot.backend.service;

import com.springboot.backend.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationSeconds;

    public JwtService(
        @Value("${jwt.secret}") String secret,
        @Value("${jwt.expiration-seconds}") long expirationSeconds) {

        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException(
                    "JWT_SECRET is required and must be a Base64-encoded key of at least 32 bytes"
            );
        }

        if (expirationSeconds <= 0) {
            throw new IllegalArgumentException(
                    "JWT_EXPIRATION_SECONDS must be greater than zero"
            );
        }

        try {
            byte[] keyBytes = Decoders.BASE64.decode(secret);

            if (keyBytes.length < 32) {
                throw new IllegalArgumentException("JWT_SECRET is too short");
            }

            this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "JWT_SECRET must be valid Base64 and decode to at least 32 bytes",
                    exception
            );
        }

        this.expirationSeconds = expirationSeconds;
    }

    public String generateToken(User user) {

        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(expirationSeconds);

        return Jwts.builder()
                .subject(user.getEmail())
                .claim("role", user.getRole())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    public String extractEmail(String token) {
        return extractClaims(token).getSubject();
    }

    public boolean isTokenValid(String token, String email) {
        Claims claims = extractClaims(token);

        return claims.getSubject().equals(email)
                && claims.getExpiration().after(new Date());
    }

    public long getExpirationSeconds() {
        return expirationSeconds;
    }

    private Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
