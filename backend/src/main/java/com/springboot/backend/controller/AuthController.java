package com.springboot.backend.controller;

import com.springboot.backend.dto.LoginRequest;
import com.springboot.backend.dto.LoginResponse;
import com.springboot.backend.dto.RegisterRequest;
import com.springboot.backend.dto.UserResponse;
import com.springboot.backend.dto.ApiErrorResponse;
import com.springboot.backend.dto.ValidationErrorResponse;
import com.springboot.backend.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Account registration and JWT login.")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    @Operation(summary = "Register an account", description = "Creates a USER account with a unique email address.")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Account created",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = UserResponse.class))),
            @ApiResponse(
                    responseCode = "400",
                    description = "Request validation failed",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ValidationErrorResponse.class))),
            @ApiResponse(
                    responseCode = "409",
                    description = "Email is already registered",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<UserResponse> register(
            @Valid @RequestBody RegisterRequest request) {

        UserResponse newUser = userService.register(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(newUser);
    }

    @PostMapping("/login")
    @Operation(summary = "Log in", description = "Validates account credentials and returns a bearer JWT.")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Credentials accepted",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = LoginResponse.class))),
            @ApiResponse(
                    responseCode = "400",
                    description = "Request validation failed",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ValidationErrorResponse.class))),
            @ApiResponse(
                    responseCode = "401",
                    description = "Email or password is incorrect",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request) {

        LoginResponse response = userService.login(request);

        return ResponseEntity.ok(response);
    }
}
