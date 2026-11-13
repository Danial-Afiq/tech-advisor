package com.springboot.backend.recommendation.trigger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

import com.springboot.backend.marketevent.MarketEventRecorded;
import com.springboot.backend.service.DeviceInventoryChanged;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Both listeners only translate their event into a trigger run, and never let a failure escape. */
@ExtendWith(MockitoExtension.class)
class InventoryRecommendationTriggerTest {

    @Mock RecommendationTriggerService service;

    @Test
    void anAddedDeviceRunsTheDeviceTrigger() {
        new InventoryRecommendationTrigger(service)
                .onInventoryChanged(new DeviceInventoryChanged(42L, DeviceInventoryChanged.Change.ADDED));

        verify(service).runForDevice(42L, "DEVICE_ADDED");
    }

    @Test
    void anEditedDeviceRunsTheDeviceTrigger() {
        new InventoryRecommendationTrigger(service)
                .onInventoryChanged(new DeviceInventoryChanged(42L, DeviceInventoryChanged.Change.UPDATED));

        verify(service).runForDevice(42L, "DEVICE_UPDATED");
    }

    @Test
    void aRecordedMarketEventRunsTheMarketEventTrigger() {
        new MarketEventRecommendationTrigger(service).onMarketEventRecorded(new MarketEventRecorded(7L));

        verify(service).runForMarketEvent(7L, RecommendationTriggerService.CAUSE_MARKET_EVENT);
    }

    @Test
    void failuresDoNotEscapeEitherListener() {
        when(service.runForDevice(anyLong(), anyString())).thenThrow(new IllegalStateException("boom"));
        when(service.runForMarketEvent(anyLong(), anyString())).thenThrow(new IllegalStateException("boom"));

        assertDoesNotThrow(() -> new InventoryRecommendationTrigger(service)
                .onInventoryChanged(new DeviceInventoryChanged(42L, DeviceInventoryChanged.Change.ADDED)));
        assertDoesNotThrow(() -> new MarketEventRecommendationTrigger(service)
                .onMarketEventRecorded(new MarketEventRecorded(7L)));
    }
}
