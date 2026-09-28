package com.springboot.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Credentials used to create a Tech Advisor account.")
public class RegisterRequest {

    @Schema(
            description = "Email address used as the account identifier.",
            example = "alex@example.com",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Email is required")
    @Email(message = "Email format is invalid")
    private String email;

    @Schema(
            description = "Account password. Must contain at least eight characters.",
            example = "correct-horse-battery-staple",
            accessMode = Schema.AccessMode.WRITE_ONLY,
            minLength = 8,
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must contain at least 8 characters")
    private String password;

    public String getEmail() {
        return email;
    }

    public String getPassword() {
        return password;
    }

    public void setEmail(String email) {
        this.email = email == null ? null : email.trim();
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
