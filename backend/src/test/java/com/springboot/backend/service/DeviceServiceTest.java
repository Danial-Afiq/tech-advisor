package com.springboot.backend.service;

import com.springboot.backend.dto.DeviceRequest;
import com.springboot.backend.exception.ResourceNotFoundException;
import com.springboot.backend.model.DevicePreference;
import com.springboot.backend.model.User;
import com.springboot.backend.model.UserDevice;
import com.springboot.backend.repository.DevicePreferenceRepository;
import com.springboot.backend.repository.ProductRepository;
import com.springboot.backend.repository.UserDeviceRepository;
import com.springboot.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;


import java.math.BigDecimal;
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
    @Mock DevicePreferenceRepository devicePreferenceRepository;
    @Mock ApplicationEventPublisher eventPublisher;

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

    @Test
    void addingADeviceAnnouncesItForRecommendations() {
        User loggedInUser = mock(User.class);
        when(loggedInUser.getId()).thenReturn(1L);
        when(userRepository.findByEmail("user-a@example.com"))
                .thenReturn(Optional.of(loggedInUser));

        UserDevice saved = mock(UserDevice.class);
        when(saved.getId()).thenReturn(42L);
        when(userDeviceRepository.save(any(UserDevice.class)))
                .thenReturn(saved);

        DeviceRequest request = new DeviceRequest();
        request.setCustomName("My phone");

        deviceService.createDevice("user-a@example.com", request);

        verify(eventPublisher).publishEvent(new DeviceInventoryChanged(42L, DeviceInventoryChanged.Change.ADDED));
    }

    @Test
    void addingADeviceWithABudgetRecordsItsPreferences() {
        User loggedInUser = mock(User.class);
        when(loggedInUser.getId()).thenReturn(1L);
        when(userRepository.findByEmail("user-a@example.com"))
                .thenReturn(Optional.of(loggedInUser));

        UserDevice saved = mock(UserDevice.class);
        when(saved.getId()).thenReturn(42L);
        when(userDeviceRepository.save(any(UserDevice.class)))
                .thenReturn(saved);
        when(devicePreferenceRepository.findById(42L))
                .thenReturn(Optional.empty());

        DeviceRequest request = new DeviceRequest();
        request.setCustomName("My phone");
        request.setBudget(new BigDecimal("1200.00"));

        deviceService.createDevice("user-a@example.com", request);

        ArgumentCaptor<DevicePreference> preference =
                ArgumentCaptor.forClass(DevicePreference.class);
        verify(devicePreferenceRepository).save(preference.capture());

        assertEquals(42L, preference.getValue().getUserDeviceId());
        assertEquals(new BigDecimal("1200.00"), preference.getValue().getBudget());
        assertEquals("SGD", preference.getValue().getCurrency());
    }

    @Test
    void addingADeviceWithoutABudgetLeavesPreferencesAlone() {
        User loggedInUser = mock(User.class);
        when(loggedInUser.getId()).thenReturn(1L);
        when(userRepository.findByEmail("user-a@example.com"))
                .thenReturn(Optional.of(loggedInUser));
        when(userDeviceRepository.save(any(UserDevice.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DeviceRequest request = new DeviceRequest();
        request.setCustomName("My phone");

        deviceService.createDevice("user-a@example.com", request);

        verify(devicePreferenceRepository, never()).save(any());
    }

    @Test
    void editingABudgetKeepsTheStoredCurrencyWhenNoneIsGiven() {
        User loggedInUser = mock(User.class);
        when(loggedInUser.getId()).thenReturn(1L);
        when(userRepository.findByEmail("user-a@example.com"))
                .thenReturn(Optional.of(loggedInUser));

        UserDevice device = mock(UserDevice.class);
        when(device.getId()).thenReturn(42L);
        when(userDeviceRepository.findByIdAndUserIdAndIsCurrentTrue(42L, 1L))
                .thenReturn(Optional.of(device));
        when(userDeviceRepository.save(device))
                .thenReturn(device);

        DevicePreference existing =
                new DevicePreference(42L, new BigDecimal("800.00"), "USD");
        when(devicePreferenceRepository.findById(42L))
                .thenReturn(Optional.of(existing));

        DeviceRequest request = new DeviceRequest();
        request.setCustomName("My phone");
        request.setBudget(new BigDecimal("1000.00"));

        deviceService.updateDevice("user-a@example.com", 42L, request);

        assertEquals(new BigDecimal("1000.00"), existing.getBudget());
        assertEquals("USD", existing.getCurrency());
        verify(devicePreferenceRepository).save(existing);
        verify(eventPublisher).publishEvent(new DeviceInventoryChanged(42L, DeviceInventoryChanged.Change.UPDATED));
    }

    @Test
    void removingADeviceDoesNotTriggerRecommendations() {
        User loggedInUser = mock(User.class);
        when(loggedInUser.getId()).thenReturn(1L);
        when(userRepository.findByEmail("user-a@example.com"))
                .thenReturn(Optional.of(loggedInUser));
        when(userDeviceRepository.findByIdAndUserIdAndIsCurrentTrue(42L, 1L))
                .thenReturn(Optional.of(mock(UserDevice.class)));

        deviceService.removeDevice("user-a@example.com", 42L);

        verifyNoInteractions(eventPublisher);
    }
}
