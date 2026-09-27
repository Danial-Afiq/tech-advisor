package com.springboot.backend.service;

import com.springboot.backend.dto.DeviceRequest;
import com.springboot.backend.exception.ResourceNotFoundException;
import com.springboot.backend.model.User;
import com.springboot.backend.model.UserDevice;
import com.springboot.backend.repository.ProductRepository;
import com.springboot.backend.repository.UserDeviceRepository;
import com.springboot.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;


import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
class DeviceServiceTest {

    @Mock UserDeviceRepository userDeviceRepository;
    @Mock UserRepository userRepository;
    @Mock ProductRepository productRepository;

    @InjectMocks DeviceService deviceService;

    @Test
    void cannotUpdateAnotherUsersDevice() {
        User loggedInUser = mock(User.class);
        when(loggedInUser.getId()).thenReturn(1L);
        when(userRepository.findByEmail("user-a@example.com"))
                .thenReturn(Optional.of(loggedInUser));

        // Device 42 does not belong to user 1.
        when(userDeviceRepository.findByIdAndUserIdAndIsCurrentTrue(42L, 1L))
                .thenReturn(Optional.empty());

        DeviceRequest request = new DeviceRequest();
        request.setCustomName("My laptop");

        assertThrows(ResourceNotFoundException.class,
                () -> deviceService.updateDevice(
                        "user-a@example.com", 42L, request));

        verify(userDeviceRepository, never()).save(any());
    }

    @Test
    void cannotRemoveAnotherUsersDevice() {
        User loggedInUser = mock(User.class);
        when(loggedInUser.getId()).thenReturn(1L);
        when(userRepository.findByEmail("user-a@example.com"))
                .thenReturn(Optional.of(loggedInUser));

        // Device 42 does not belong to user 1.
        when(userDeviceRepository.findByIdAndUserIdAndIsCurrentTrue(42L, 1L))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> deviceService.removeDevice(
                        "user-a@example.com", 42L));

        verify(userDeviceRepository, never()).save(any());
    }

    @Test
    void removingOwnDeviceMarksItAsNotCurrent() {
        User loggedInUser = mock(User.class);
        when(loggedInUser.getId()).thenReturn(1L);
        when(userRepository.findByEmail("user-a@example.com"))
                .thenReturn(Optional.of(loggedInUser));

        UserDevice device = mock(UserDevice.class);
        when(userDeviceRepository.findByIdAndUserIdAndIsCurrentTrue(42L, 1L))
                .thenReturn(Optional.of(device));

        deviceService.removeDevice("user-a@example.com", 42L);

        verify(device).setCurrent(false);
        verify(userDeviceRepository).save(device);
        verify(userDeviceRepository, never()).delete(any());
    }

    @Test
    void creatingDeviceAssignsItToLoggedInUser() {
        User loggedInUser = mock(User.class);
        when(loggedInUser.getId()).thenReturn(1L);
        when(userRepository.findByEmail("user-a@example.com"))
                .thenReturn(Optional.of(loggedInUser));

        DeviceRequest request = new DeviceRequest();
        request.setCustomName("My laptop");

        when(userDeviceRepository.save(any(UserDevice.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        deviceService.createDevice("user-a@example.com", request);

        ArgumentCaptor<UserDevice> savedDevice =
                ArgumentCaptor.forClass(UserDevice.class);
        verify(userDeviceRepository).save(savedDevice.capture());

        assertEquals(1L, savedDevice.getValue().getUserId());
        assertEquals("My laptop", savedDevice.getValue().getCustomName());
    }

    @Test
    void updatingOwnDeviceReplacesItsDetails() {
        User loggedInUser = mock(User.class);
        when(loggedInUser.getId()).thenReturn(1L);
        when(userRepository.findByEmail("user-a@example.com"))
                .thenReturn(Optional.of(loggedInUser));

        UserDevice device = new UserDevice(1L, null, "Old laptop");
        when(userDeviceRepository.findByIdAndUserIdAndIsCurrentTrue(42L, 1L))
                .thenReturn(Optional.of(device));
        when(userDeviceRepository.save(device))
                .thenReturn(device);

        DeviceRequest request = new DeviceRequest();
        request.setCustomName("New laptop");
        request.setSatisfactionScore(80);

        deviceService.updateDevice("user-a@example.com", 42L, request);

        assertEquals("New laptop", device.getCustomName());
        assertEquals(80, device.getSatisfactionScore());
        verify(userDeviceRepository).save(device);
    }
}