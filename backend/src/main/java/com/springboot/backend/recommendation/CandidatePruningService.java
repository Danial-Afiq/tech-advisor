package com.springboot.backend.recommendation;

import com.springboot.backend.currency.CurrencyConversionService;
import com.springboot.backend.currency.CurrencyConversionService.Price;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reduces the catalogue to products that are compatible and
 * affordable for one owned device before any AI processing.
 *
 * <p>This exists so hard constraints never reach the model. Category and
 * budget are exact facts, and active product-dismissal cooldowns are enforced
 * before candidates are loaded. A language model asked to honour them is both
 * unreliable and expensive, since every product that survives costs embedding
 * comparisons, retrieval and prompt tokens downstream. Everything here is
 * deterministic querying and currency conversion - no embeddings, retrieval,
 * or model call.
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
    private final CurrencyConversionService currencyConversionService;

    public CandidatePruningService(
            UserDeviceRepository userDeviceRepository,
            DevicePreferenceRepository devicePreferenceRepository,
            ProductRepository productRepository,
            CurrencyConversionService currencyConversionService) {

        this.userDeviceRepository =
                userDeviceRepository;
        this.devicePreferenceRepository =
                devicePreferenceRepository;
        this.productRepository = productRepository;
        this.currencyConversionService = currencyConversionService;
    }

    @Transactional(readOnly = true)
    public List<CandidateProduct> getViableCandidates(
            Long userDeviceId) {

        Context context = resolve(userDeviceId);

        List<CandidateProduct> candidates = productRepository.findPricedCandidates(
                context.category(), context.ownedProductId(), context.userId());
        if (candidates.isEmpty()) {
            return candidates;
        }

        List<CurrencyConversionService.ConversionResult> conversions =
                currencyConversionService.convertAllForComparison(
                        candidates.stream()
                                .map(candidate -> new Price(
                                        candidate.getLatestPrice(),
                                        candidate.getCurrency()))
                                .toList(),
                        context.preference().getCurrency()
                );

        BigDecimal budget = context.preference().getBudget();
        List<CandidateProduct> affordable = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            CandidateProduct candidate = candidates.get(index);
            var conversion = conversions.get(index);
            if (conversion.status() == CurrencyConversionService.Status.UNAVAILABLE
                    || conversion.amount() == null) {
                LOG.warn(
                        "Candidate {} excluded because {} could not be converted to budget currency {}",
                        candidate.getProductId(),
                        candidate.getCurrency(),
                        context.preference().getCurrency()
                );
                continue;
            }
            if (conversion.amount().compareTo(budget) <= 0) {
                affordable.add(new BudgetCandidate(
                        candidate.getProductId(),
                        candidate.getBrand(),
                        candidate.getModelName(),
                        conversion.amount(),
                        conversion.currency()
                ));
            }
        }
        affordable.sort(Comparator
                .comparing(CandidateProduct::getLatestPrice)
                .thenComparing(CandidateProduct::getProductId));
        return affordable;
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

    private record BudgetCandidate(
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

    private record Context(
            Long userId,
            String category,
            Long ownedProductId,
            DevicePreference preference
    ) {
    }
}
