package com.springboot.backend.controller;

import com.springboot.backend.config.OpenApiConfig;
import com.springboot.backend.dto.ApiErrorResponse;
import com.springboot.backend.dto.UserResponse;
import com.springboot.backend.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profile")
@Tag(name = "Profile", description = "Authenticated account profile.")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class ProfileController {

    private final UserService userService;

    public ProfileController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @Operation(summary = "Get the current profile")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Current account profile",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = UserResponse.class))),
            @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content),
            @ApiResponse(
                    responseCode = "409",
                    description = "The authenticated account no longer exists",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<UserResponse> getProfile(
            Authentication authentication) {

        String email = authentication.getName();

        UserResponse profile =
                userService.getProfile(email);

        return ResponseEntity.ok(profile);
    }
}
