package com.springboot.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "JWT credentials returned after a successful login.")
public class LoginResponse {

    @Schema(description = "Signed JSON Web Token.", example = "eyJhbGciOiJIUzI1NiJ9...")
    private final String token;

    @Schema(description = "Authorization header scheme.", example = "Bearer", allowableValues = "Bearer")
    private final String tokenType;

    @Schema(description = "Token lifetime in seconds.", example = "3600")
    private final long expiresIn;

    @Schema(description = "Role encoded in the JWT.", example = "USER", allowableValues = {"USER", "ADMIN"})
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
