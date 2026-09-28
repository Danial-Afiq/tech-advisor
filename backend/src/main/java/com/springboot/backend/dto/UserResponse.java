package com.springboot.backend.dto;

import com.springboot.backend.model.User;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "Public account details. Password data is never returned.")
public class UserResponse {

    @Schema(description = "User identifier.", example = "42", accessMode = Schema.AccessMode.READ_ONLY)
    private Long id;

    @Schema(description = "Normalised account email.", example = "alex@example.com", accessMode = Schema.AccessMode.READ_ONLY)
    private String email;

    @Schema(description = "Application role.", example = "USER", allowableValues = {"USER", "ADMIN"}, accessMode = Schema.AccessMode.READ_ONLY)
    private String role;

    @Schema(description = "Account creation timestamp.", example = "2026-09-28T10:15:30+08:00", accessMode = Schema.AccessMode.READ_ONLY)
    private OffsetDateTime createdAt;

    public UserResponse(User user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.role = user.getRole();
        this.createdAt = user.getCreatedAt();
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getRole() {
        return role;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
