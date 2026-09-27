package com.springboot.backend.controller;

import com.springboot.backend.dto.AdminLoginRequest;
import com.springboot.backend.dto.LoginResponse;
import com.springboot.backend.service.AdminAuthenticationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/admin")
public class AdminAuthController {

    private final AdminAuthenticationService
            adminAuthenticationService;

    public AdminAuthController(
            AdminAuthenticationService
                    adminAuthenticationService) {

        this.adminAuthenticationService =
                adminAuthenticationService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody
            AdminLoginRequest request) {

        LoginResponse response =
                adminAuthenticationService.login(request);

        return ResponseEntity.ok(response);
    }
}