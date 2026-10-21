package com.springboot.backend.recommendation;

import com.springboot.backend.currency.CurrencyConversionService;
import com.springboot.backend.exception.ResourceNotFoundException;
import com.springboot.backend.model.DevicePreference;
import com.springboot.backend.model.Product;
import com.springboot.backend.model.UserDevice;
import com.springboot.backend.repository.DevicePreferenceRepository;
import com.springboot.backend.repository.CandidateProduct;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    @Mock private CurrencyConversionService currencyConversionService;

    @InjectMocks private CandidatePruningService service;

    @Test
    void queriesPricedProductsForTheOwnedDevicesCategory() {
        givenDeviceWithProduct("SMARTPHONE", OWNED_PRODUCT_ID);
        givenBudget(new BigDecimal("1000.00"), "SGD");
        when(productRepository.findPricedCandidates(anyString(), anyLong()))
                .thenReturn(List.of());

        service.getViableCandidates(DEVICE_ID);

        verify(productRepository).findPricedCandidates(
                "SMARTPHONE", OWNED_PRODUCT_ID);
        verifyNoInteractions(currencyConversionService);
    }

    @Test
    void comparesConvertedPricesAndReturnsBudgetCurrencyValues() {
        givenDeviceWithProduct("SMARTPHONE", OWNED_PRODUCT_ID);
        givenBudget(new BigDecimal("200.00"), "SGD");
        CandidateProduct overBudget = candidate(1L, "185.00", "USD");
        CandidateProduct affordable = candidate(2L, "149.99", "USD");
        when(productRepository.findPricedCandidates("SMARTPHONE", OWNED_PRODUCT_ID))
                .thenReturn(List.of(overBudget, affordable));
        when(currencyConversionService.convertAllForComparison(anyList(), eq("SGD")))
                .thenReturn(List.of(
                        conversion("236.62", "SGD", CurrencyConversionService.Status.CONVERTED),
                        conversion("191.84", "SGD", CurrencyConversionService.Status.CONVERTED)
                ));

        List<CandidateProduct> result = service.getViableCandidates(DEVICE_ID);

        assertEquals(List.of(2L), result.stream().map(CandidateProduct::getProductId).toList());
        assertEquals(0, new BigDecimal("191.84").compareTo(result.getFirst().getLatestPrice()));
        assertEquals("SGD", result.getFirst().getCurrency());
    }

    @Test
    void excludesForeignPriceWhenConversionIsUnavailable() {
        givenDeviceWithProduct("SMARTPHONE", OWNED_PRODUCT_ID);
        givenBudget(new BigDecimal("200.00"), "SGD");
        CandidateProduct candidate = candidate(1L, "99.00", "USD");
        when(productRepository.findPricedCandidates("SMARTPHONE", OWNED_PRODUCT_ID))
                .thenReturn(List.of(candidate));
        when(currencyConversionService.convertAllForComparison(anyList(), eq("SGD")))
                .thenReturn(List.of(conversion(
                        "99.00", "USD", CurrencyConversionService.Status.UNAVAILABLE)));

        assertTrue(service.getViableCandidates(DEVICE_ID).isEmpty());
    }

    @Test
    void rejectsAnUnknownDevice() {
        when(userDeviceRepository.findByIdAndIsCurrentTrue(DEVICE_ID)).thenReturn(Optional.empty());

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
        when(userDeviceRepository.findByIdAndIsCurrentTrue(DEVICE_ID)).thenReturn(Optional.of(device));
        when(devicePreferenceRepository.findById(DEVICE_ID))
                .thenReturn(Optional.of(new DevicePreference(DEVICE_ID, new BigDecimal("900"), "SGD")));

        var exception = assertThrows(ResourceNotFoundException.class,
                () -> service.getViableCandidates(DEVICE_ID));

        assertTrue(exception.getMessage().contains("catalogue link"));
        verifyNoInteractions(productRepository);
    }


    @Test
    void rejectsARemovedDevice() {
        when(userDeviceRepository.findByIdAndIsCurrentTrue(DEVICE_ID))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.getViableCandidates(DEVICE_ID));

        verifyNoInteractions(devicePreferenceRepository, productRepository);
    }

    private void givenDeviceWithProduct(String category, Long productId) {
        Product product = mock(Product.class);
        lenient().when(product.getId()).thenReturn(productId);
        lenient().when(product.getCategory()).thenReturn(category);

        UserDevice device = mock(UserDevice.class);
        lenient().when(device.getProduct()).thenReturn(product);

        when(userDeviceRepository.findByIdAndIsCurrentTrue(DEVICE_ID)).thenReturn(Optional.of(device));
    }

    private void givenBudget(BigDecimal budget, String currency) {
        when(devicePreferenceRepository.findById(DEVICE_ID))
                .thenReturn(Optional.of(new DevicePreference(DEVICE_ID, budget, currency)));
    }

    private static CandidateProduct candidate(Long id, String price, String currency) {
        return new TestCandidate(
                id, "Brand", "Model " + id, new BigDecimal(price), currency);
    }

    private static CurrencyConversionService.ConversionResult conversion(
            String amount,
            String currency,
            CurrencyConversionService.Status status) {
        return new CurrencyConversionService.ConversionResult(
                new BigDecimal(amount), currency, status, null);
    }

    private record TestCandidate(
            Long productId,
            String brand,
            String modelName,
            BigDecimal latestPrice,
            String currency) implements CandidateProduct {

        @Override public Long getProductId() { return productId; }
        @Override public String getBrand() { return brand; }
        @Override public String getModelName() { return modelName; }
        @Override public BigDecimal getLatestPrice() { return latestPrice; }
        @Override public String getCurrency() { return currency; }
    }
}
