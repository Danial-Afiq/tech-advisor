package com.springboot.backend.recommendation;

import com.springboot.backend.exception.ResourceNotFoundException;
import com.springboot.backend.model.DevicePreference;
import com.springboot.backend.model.Product;
import com.springboot.backend.model.UserDevice;
import com.springboot.backend.repository.CandidateProduct;
import com.springboot.backend.repository.DevicePreferenceRepository;
import com.springboot.backend.repository.ProductRepository;
import com.springboot.backend.repository.UserDeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Reduces the catalogue to products that are compatible and
 * affordable for one owned device before any AI processing.
 *
 * <p>Category, budget and active product-dismissal cooldowns are
 * deterministic constraints. Products that fail these constraints
 * must not reach the model.
 */
@Service
public class CandidatePruningService {

    private static final Logger LOG =
            LoggerFactory.getLogger(
                    CandidatePruningService.class
            );

    private final UserDeviceRepository
            userDeviceRepository;
    private final DevicePreferenceRepository
            devicePreferenceRepository;
    private final ProductRepository productRepository;

    public CandidatePruningService(
            UserDeviceRepository userDeviceRepository,
            DevicePreferenceRepository
                    devicePreferenceRepository,
            ProductRepository productRepository) {

        this.userDeviceRepository =
                userDeviceRepository;
        this.devicePreferenceRepository =
                devicePreferenceRepository;
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public List<CandidateProduct> getViableCandidates(
            Long userDeviceId) {

        Context context = resolve(userDeviceId);

        List<CandidateProduct> candidates =
                productRepository
                        .findCompatibleCandidates(
                                context.category(),
                                context.preference()
                                        .getBudget(),
                                context.ownedProductId(),
                                context.userId()
                        );

        warnOnCurrencyMismatch(
                candidates,
                context.preference()
        );

        return candidates;
    }

    /**
     * Loads the owned device and its upgrade preferences.
     *
     * <p>A device without a catalogue product cannot be evaluated
     * because its product category is unknown.
     */
    private Context resolve(Long userDeviceId) {

        UserDevice device = userDeviceRepository
                .findByIdAndIsCurrentTrue(userDeviceId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Owned device "
                                        + userDeviceId
                                        + " not found"
                        )
                );

        DevicePreference preference =
                devicePreferenceRepository
                        .findById(userDeviceId)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Upgrade preferences "
                                                + "not configured "
                                                + "for device "
                                                + userDeviceId
                                )
                        );

        Product owned = device.getProduct();

        if (owned == null) {
            throw new ResourceNotFoundException(
                    "Owned device "
                            + userDeviceId
                            + " has no catalogue link, "
                            + "so its category cannot "
                            + "be determined"
            );
        }

        return new Context(
                device.getUserId(),
                owned.getCategory(),
                owned.getId(),
                preference
        );
    }

    /**
     * Warns when candidate prices and the user's budget use
     * different currencies because conversion is not implemented.
     */
    private void warnOnCurrencyMismatch(
            List<CandidateProduct> candidates,
            DevicePreference preference) {

        String budgetCurrency =
                preference.getCurrency();

        if (budgetCurrency == null) {
            return;
        }

        candidates.stream()
                .map(CandidateProduct::getCurrency)
                .filter(currency ->
                        currency != null
                                && !budgetCurrency
                                        .equalsIgnoreCase(
                                                currency.trim()
                                        )
                )
                .distinct()
                .forEach(currency ->
                        LOG.warn(
                                "Candidate priced in {} compared "
                                        + "against a {} budget "
                                        + "without conversion; "
                                        + "single-currency assumption "
                                        + "may not hold",
                                currency.trim(),
                                budgetCurrency
                        )
                );
    }

    private record Context(
            Long userId,
            String category,
            Long ownedProductId,
            DevicePreference preference
    ) {
    }
}
