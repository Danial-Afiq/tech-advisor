package com.springboot.backend.dto;

import com.springboot.backend.model.User;
import java.time.OffsetDateTime;

public class UserResponse {

    private Long id;
    private String email;
    private String role;
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