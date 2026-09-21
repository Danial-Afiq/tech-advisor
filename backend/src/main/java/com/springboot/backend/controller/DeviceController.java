package com.springboot.backend.controller;

import com.springboot.backend.dto.DeviceRequest;
import com.springboot.backend.dto.DeviceResponse;
import com.springboot.backend.service.DeviceService;
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
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(
            DeviceService deviceService) {

        this.deviceService = deviceService;
    }

    @PostMapping
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
    public ResponseEntity<DeviceResponse> getDevice(
            Authentication authentication,
            @PathVariable Long deviceId) {

        DeviceResponse device =
                deviceService.getDevice(
                        authentication.getName(),
                        deviceId
                );

        return ResponseEntity.ok(device);
    }

    @PutMapping("/{deviceId}")
    public ResponseEntity<DeviceResponse> updateDevice(
            Authentication authentication,
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
    public ResponseEntity<Void> removeDevice(
            Authentication authentication,
            @PathVariable Long deviceId) {

        deviceService.removeDevice(
                authentication.getName(),
                deviceId
        );

        return ResponseEntity.noContent().build();
    }
}