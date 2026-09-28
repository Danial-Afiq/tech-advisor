package com.springboot.backend.dto;

public class LoginResponse {

    private final String token;
    private final String tokenType;
    private final long expiresIn;
    private final String role;

    public LoginResponse(String token, long expiresIn, String role) {
        this.token = token;
        this.tokenType = "Bearer";
        this.expiresIn = expiresIn;
        this.role = role;
    }

    public String getToken() {
        return token;
    }

    public String getTokenType() {
        return tokenType;
    }

    public long getExpiresIn() {
        return expiresIn;
    }

    public String getRole() {
        return role;
    }
}