package com.springboot.backend.recommendation;

import com.springboot.backend.exception.ResourceNotFoundException;
import com.springboot.backend.model.DevicePreference;
import com.springboot.backend.model.Product;
import com.springboot.backend.model.UserDevice;
import com.springboot.backend.repository.DevicePreferenceRepository;
import com.springboot.backend.repository.ProductRepository;
import com.springboot.backend.repository.UserDeviceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Guards the inputs to the shortlisting query. Getting the category or the
 * owned product wrong here would not fail loudly - it would quietly return a
 * plausible but wrong candidate set.
 */
@ExtendWith(MockitoExtension.class)
class CandidatePruningServiceTest {

    private static final Long DEVICE_ID = 42L;
    private static final Long OWNED_PRODUCT_ID = 812L;

    @Mock private UserDeviceRepository userDeviceRepository;
    @Mock private DevicePreferenceRepository devicePreferenceRepository;
    @Mock private ProductRepository productRepository;

    @InjectMocks private CandidatePruningService service;

    @Test
    void queriesWithTheOwnedDevicesCategoryBudgetAndProduct() {
        givenDeviceWithProduct("SMARTPHONE", OWNED_PRODUCT_ID);
        givenBudget(new BigDecimal("1000.00"), "SGD");
        when(productRepository.findCompatibleCandidates(anyString(), any(), anyLong()))
                .thenReturn(List.of());

        service.getViableCandidates(DEVICE_ID);

        verify(productRepository).findCompatibleCandidates(
                "SMARTPHONE", new BigDecimal("1000.00"), OWNED_PRODUCT_ID);
    }

    @Test
    void rejectsAnUnknownDevice() {
        when(userDeviceRepository.findById(DEVICE_ID)).thenReturn(Optional.empty());

        var exception = assertThrows(ResourceNotFoundException.class,
                () -> service.getViableCandidates(DEVICE_ID));

        assertTrue(exception.getMessage().contains("not found"));
        verifyNoInteractions(productRepository);
    }

    @Test
    void rejectsADeviceWithNoPreferencesConfigured() {
        givenDeviceWithProduct("SMARTPHONE", OWNED_PRODUCT_ID);
        when(devicePreferenceRepository.findById(DEVICE_ID)).thenReturn(Optional.empty());

        var exception = assertThrows(ResourceNotFoundException.class,
                () -> service.getViableCandidates(DEVICE_ID));

        assertTrue(exception.getMessage().contains("preferences"));
        verifyNoInteractions(productRepository);
    }

    @Test
    void rejectsADeviceWithNoCatalogueLinkRatherThanQueryingWithANullCategory() {
        // The catalogue link is nullable by design, so this is a real state.
        // Falling through would run the filter with category = null, which
        // returns nothing and looks indistinguishable from "no candidates".
        UserDevice device = mock(UserDevice.class);
        when(device.getProduct()).thenReturn(null);
        when(userDeviceRepository.findById(DEVICE_ID)).thenReturn(Optional.of(device));
        when(devicePreferenceRepository.findById(DEVICE_ID))
                .thenReturn(Optional.of(new DevicePreference(DEVICE_ID, new BigDecimal("900"), "SGD")));

        var exception = assertThrows(ResourceNotFoundException.class,
                () -> service.getViableCandidates(DEVICE_ID));

        assertTrue(exception.getMessage().contains("catalogue link"));
        verifyNoInteractions(productRepository);
    }



    private void givenDeviceWithProduct(String category, Long productId) {
        Product product = mock(Product.class);
        lenient().when(product.getId()).thenReturn(productId);
        lenient().when(product.getCategory()).thenReturn(category);

        UserDevice device = mock(UserDevice.class);
        lenient().when(device.getProduct()).thenReturn(product);

        when(userDeviceRepository.findById(DEVICE_ID)).thenReturn(Optional.of(device));
    }

    private void givenBudget(BigDecimal budget, String currency) {
        when(devicePreferenceRepository.findById(DEVICE_ID))
                .thenReturn(Optional.of(new DevicePreference(DEVICE_ID, budget, currency)));
    }
}
