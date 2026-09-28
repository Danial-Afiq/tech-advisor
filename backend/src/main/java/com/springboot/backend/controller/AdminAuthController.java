package com.springboot.backend.controller;

import com.springboot.backend.dto.ApiErrorResponse;
import com.springboot.backend.dto.LoginRequest;
import com.springboot.backend.dto.LoginResponse;
import com.springboot.backend.dto.ValidationErrorResponse;
import com.springboot.backend.service.AdminAuthenticationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/admin")
@Tag(name = "Authentication", description = "Account registration and JWT login.")
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
    @Operation(summary = "Log in as an administrator", description = "Validates ADMIN credentials and returns a bearer JWT.")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Administrator credentials accepted",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = LoginResponse.class))),
            @ApiResponse(
                    responseCode = "400",
                    description = "Request validation failed",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ValidationErrorResponse.class))),
            @ApiResponse(
                    responseCode = "401",
                    description = "Email or password is incorrect, or the account is not an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody
            LoginRequest request) {

        LoginResponse response =
                adminAuthenticationService.login(request);

        return ResponseEntity.ok(response);
    }
}
