package com.springboot.backend.controller;

import com.springboot.backend.config.OpenApiConfig;
import com.springboot.backend.dto.ApiErrorResponse;
import com.springboot.backend.dto.DeviceRequest;
import com.springboot.backend.dto.DeviceResponse;
import com.springboot.backend.dto.ValidationErrorResponse;
import com.springboot.backend.service.DeviceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/devices")
@Tag(name = "Owned devices", description = "Create and manage devices belonging to the authenticated user.")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(
            DeviceService deviceService) {

        this.deviceService = deviceService;
    }

    @PostMapping
    @Operation(summary = "Add an owned device")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Device created",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = DeviceResponse.class))),
            @ApiResponse(
                    responseCode = "400",
                    description = "Validation, device identity, or JSON field error",
                    content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {
                            ValidationErrorResponse.class,
                            ApiErrorResponse.class
                    }))),
            @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content),
            @ApiResponse(
                    responseCode = "404",
                    description = "User or referenced product was not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<DeviceResponse> createDevice(
            Authentication authentication,
            @Valid @RequestBody DeviceRequest request) {

        DeviceResponse createdDevice =
                deviceService.createDevice(
                        authentication.getName(),
                        request
                );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(createdDevice);
    }

    @GetMapping
    @Operation(summary = "List current owned devices", description = "Returns only devices that have not been removed.")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Current devices, newest first",
                    content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = DeviceResponse.class)))),
            @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content),
            @ApiResponse(
                    responseCode = "404",
                    description = "User was not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<List<DeviceResponse>>
            getCurrentDevices(
                    Authentication authentication) {

        List<DeviceResponse> devices =
                deviceService.getCurrentDevices(
                        authentication.getName()
                );

        return ResponseEntity.ok(devices);
    }

    @GetMapping("/{deviceId}")
    @Operation(summary = "Get an owned device")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Owned device",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = DeviceResponse.class))),
            @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content),
            @ApiResponse(
                    responseCode = "404",
                    description = "User or current owned device was not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<DeviceResponse> getDevice(
            Authentication authentication,
            @Parameter(description = "Owned-device identifier", example = "27")
            @PathVariable Long deviceId) {

        DeviceResponse device =
                deviceService.getDevice(
                        authentication.getName(),
                        deviceId
                );

        return ResponseEntity.ok(device);
    }

    @PutMapping("/{deviceId}")
    @Operation(summary = "Replace an owned device", description = "Replaces all editable fields for a current owned device.")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Device updated",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = DeviceResponse.class))),
            @ApiResponse(
                    responseCode = "400",
                    description = "Validation, device identity, or JSON field error",
                    content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {
                            ValidationErrorResponse.class,
                            ApiErrorResponse.class
                    }))),
            @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content),
            @ApiResponse(
                    responseCode = "404",
                    description = "User, device, or referenced product was not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<DeviceResponse> updateDevice(
            Authentication authentication,
            @Parameter(description = "Owned-device identifier", example = "27")
            @PathVariable Long deviceId,
            @Valid @RequestBody DeviceRequest request) {

        DeviceResponse updatedDevice =
                deviceService.updateDevice(
                        authentication.getName(),
                        deviceId,
                        request
                );

        return ResponseEntity.ok(updatedDevice);
    }

    @DeleteMapping("/{deviceId}")
    @Operation(summary = "Remove an owned device", description = "Soft-removes the device from the user's current-device list.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Device removed", content = @Content),
            @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content),
            @ApiResponse(
                    responseCode = "404",
                    description = "User or current owned device was not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<Void> removeDevice(
            Authentication authentication,
            @Parameter(description = "Owned-device identifier", example = "27")
            @PathVariable Long deviceId) {

        deviceService.removeDevice(
                authentication.getName(),
                deviceId
        );

        return ResponseEntity.noContent().build();
    }
}
