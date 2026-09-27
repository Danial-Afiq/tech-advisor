package com.springboot.backend.recommendation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.springboot.backend.exception.ResourceNotFoundException;
import com.springboot.backend.repository.UserDeviceRepository;
import com.springboot.backend.service.DeviceInventoryChanged;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InventoryRecommendationTriggerTest {

    @Mock DeterministicRecommendationService service;
    @Mock UserDeviceRepository userDeviceRepository;

    @InjectMocks InventoryRecommendationTrigger trigger;

    @Test
    void anEvaluableDeviceIsEvaluated() {
        when(userDeviceRepository.isEvaluable(42L)).thenReturn(true);
        when(service.evaluateAndPersist(42L))
                .thenReturn(new DeterministicRecommendationService.PersistedEvaluation(null, 2, 0));

        trigger.onInventoryChanged(new DeviceInventoryChanged(42L));

        verify(service).evaluateAndPersist(42L);
    }

    @Test
    void aDeviceThatCannotBeEvaluatedYetIsSkipped() {
        when(userDeviceRepository.isEvaluable(42L)).thenReturn(false);

        trigger.onInventoryChanged(new DeviceInventoryChanged(42L));

        verify(service, never()).evaluateAndPersist(any());
    }

    @Test
    void aFailedEvaluationDoesNotEscape() {
        when(userDeviceRepository.isEvaluable(42L)).thenReturn(true);
        when(service.evaluateAndPersist(42L))
                .thenThrow(new ResourceNotFoundException("No specifications recorded for owned product 7"));

        assertDoesNotThrow(() -> trigger.onInventoryChanged(new DeviceInventoryChanged(42L)));
    }
}
