package com.springboot.backend.recommendation.trigger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.springboot.backend.model.DevicePreference;
import com.springboot.backend.model.Product;
import com.springboot.backend.model.UserDevice;
import com.springboot.backend.recommendation.AssessRequest;
import com.springboot.backend.recommendation.CandidateEvaluation;
import com.springboot.backend.recommendation.classification.UpgradeClassification;
import com.springboot.backend.repository.CandidateProduct;
import com.springboot.backend.repository.DevicePreferenceRepository;
import com.springboot.backend.repository.ProductRepository;
import com.springboot.backend.repository.UserDeviceRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssessRequestFactoryTest {

    private static final Clock TODAY = Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC);

    @Mock UserDeviceRepository devices;
    @Mock DevicePreferenceRepository preferences;
    @Mock ProductRepository products;

    private AssessRequestFactory factory;
    private UserDevice device;
    private DevicePreference preference;
    private Product candidate;
    private CandidateEvaluation.Classified classified;

    @BeforeEach
    void setUp() {
        factory = new AssessRequestFactory(devices, preferences, products, TODAY);

        device = new UserDevice(5L, new Product("Samsung", "Galaxy S22", "SMARTPHONE", "VERIFIED"), null);
        device.setCondition("fair");
        device.setSatisfactionScore(45);
        device.setPurchaseDate(LocalDate.of(2024, 4, 5));
        device.setUseCases("[\"photography\", \"gaming\"]");

        preference = new DevicePreference(9L, new BigDecimal("1200.00"), "SGD");
        preference.setUpgradeUrgency("SOMEWHAT_URGENT");
        preference.setBrandFlexibility("FLEXIBLE");
        preference.setPriorities("{\"battery\": 5, \"camera\": 4}");
        preference.setPainPoints("[\"Battery drains by lunchtime\", \"Weak low-light camera\"]");

        candidate = new Product("Samsung", "Galaxy S25", "SMARTPHONE", "VERIFIED");
        candidate.setReleaseDate(LocalDate.of(2026, 9, 5));

        CandidateProduct shortlisted = mock(CandidateProduct.class);
        when(shortlisted.getProductId()).thenReturn(812L);
        UpgradeClassification classification = mock(UpgradeClassification.class);
        lenient().when(classification.toComputed(any()))
                .thenAnswer(call -> new AssessRequest.Computed(Map.of(), 12.5, null, call.getArgument(0)));
        lenient().when(classification.toAnalysis())
                .thenReturn(new AssessRequest.Analysis("WORTH_CONSIDERING", 0.7, List.of("battery")));
        classified = new CandidateEvaluation.Classified(shortlisted, classification);

        when(devices.findById(9L)).thenReturn(Optional.of(device));
        when(preferences.findById(9L)).thenReturn(Optional.of(preference));
        when(products.findById(812L)).thenReturn(Optional.of(candidate));
    }

    @Test
    void aCompleteContextBuildsTheWireRequestWithFinishedNumbers() {
        AssessRequest.TriggerEvent event = new AssessRequest.TriggerEvent(
                "PRICE_CHANGE", "Galaxy S25 drops", Map.of("price", 1199), Map.of("price", 1099));

        AssessRequestFactory.Built built = factory.build(9L, classified, event);

        assertTrue(built.complete(), () -> "unexpected gaps: " + built.missing());
        AssessRequest request = built.request();
        AssessRequest.OwnedDevice owned = request.userContext().ownedDevice();
        assertEquals("Samsung Galaxy S22", owned.name());
        assertEquals(30, owned.deviceAgeMonths());
        assertEquals("FAIR", owned.condition());
        assertEquals(List.of("photography", "gaming"), owned.useCases());

        AssessRequest.Preferences prefs = request.userContext().preferences();
        assertEquals(1200.0, prefs.budget());
        assertEquals(Map.of("battery", 5, "camera", 4), prefs.priorities());
        assertEquals("Battery drains by lunchtime; Weak low-light camera", prefs.painPoints());

        assertEquals(812L, request.candidate().productId());
        assertEquals("Samsung Galaxy S25", request.candidate().name());
        assertEquals(30, request.candidate().ageDays());
        assertEquals(event, request.computed().triggerEvent());
        assertEquals("WORTH_CONSIDERING", request.analysis().verdict());
    }

    @Test
    void missingContextIsReportedRatherThanInvented() {
        device.setCondition(null);
        device.setSatisfactionScore(null);
        device.setPurchaseDate(null);
        preference.setUpgradeUrgency(null);
        preference.setBrandFlexibility("SAME_ECOSYSTEM");
        candidate.setReleaseDate(null);

        AssessRequestFactory.Built built = factory.build(9L, classified, null);

        assertFalse(built.complete());
        assertNull(built.request());
        assertEquals(List.of(
                "user_devices.condition",
                "user_devices.satisfaction_score",
                "user_devices.purchase_date",
                "device_preferences.upgrade_urgency",
                "device_preferences.brand_flexibility",
                "products.release_date (candidate 812)"), built.missing());
    }

    @Test
    void aPriorityOutsideTheFactorVocabularyBlocksTheCall() {
        preference.setPriorities("{\"battery\": 5, \"vibes\": 3}");

        AssessRequestFactory.Built built = factory.build(9L, classified, null);

        assertEquals(List.of("device_preferences.priorities (invalid entry 'vibes')"), built.missing());
    }

    @Test
    void emptyPainPointsAreSentAsNone() {
        preference.setPainPoints("{}");

        assertNull(factory.build(9L, classified, null).request().userContext().preferences().painPoints());
    }
}
