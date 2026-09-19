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
 * Reduces the catalogue to the products that are actually compatible and
 * affordable for one owned device, before any AI runs.
 *
 * <p>This exists so hard constraints never reach the model. Category and
 * budget are exact facts; a language model asked to honour them is both
 * unreliable and expensive, since every product that survives costs embedding
 * comparisons, retrieval and prompt tokens downstream. Everything here is
 * ordinary relational querying - no embeddings, no retrieval, no model call.
 */
@Service
public class CandidatePruningService {

    private static final Logger LOG = LoggerFactory.getLogger(CandidatePruningService.class);

    private final UserDeviceRepository userDeviceRepository;
    private final DevicePreferenceRepository devicePreferenceRepository;
    private final ProductRepository productRepository;

    public CandidatePruningService(
            UserDeviceRepository userDeviceRepository,
            DevicePreferenceRepository devicePreferenceRepository,
            ProductRepository productRepository) {

        this.userDeviceRepository = userDeviceRepository;
        this.devicePreferenceRepository = devicePreferenceRepository;
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public List<CandidateProduct> getViableCandidates(Long userDeviceId) {
        Context context = resolve(userDeviceId);

        List<CandidateProduct> candidates = productRepository.findCompatibleCandidates(
                context.category(), context.preference().getBudget(), context.ownedProductId());

        warnOnCurrencyMismatch(candidates, context.preference());
        return candidates;
    }

    /**
     * Loads everything the filter needs, failing loudly when the device cannot
     * be evaluated at all.
     *
     * <p>A device with no catalogue link is rejected rather than queried with a
     * null category: the whole point of the step is that a candidate matches
     * the owned device's category, and there is no honest way to do that when
     * we do not know what the user owns.
     */
    private Context resolve(Long userDeviceId) {
        UserDevice device = userDeviceRepository.findById(userDeviceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Owned device " + userDeviceId + " not found"));

        DevicePreference preference = devicePreferenceRepository.findById(userDeviceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Upgrade preferences not configured for device " + userDeviceId));

        Product owned = device.getProduct();
        if (owned == null) {
            throw new ResourceNotFoundException(
                    "Owned device " + userDeviceId + " has no catalogue link, "
                            + "so its category cannot be determined");
        }

        return new Context(owned.getCategory(), owned.getId(), preference);
    }

    /**
     * Budget and price are compared as bare numbers on the assumption that the
     * system runs in a single currency. That assumption is not enforced
     * anywhere upstream - the ingestion price payload accepts any ISO code - so
     * a mismatch is surfaced here rather than passing silently. Converting
     * would mean inventing an exchange rate, which is not this layer's call.
     */
    private void warnOnCurrencyMismatch(
            List<CandidateProduct> candidates, DevicePreference preference) {

        String budgetCurrency = preference.getCurrency();
        if (budgetCurrency == null) {
            return;
        }

        candidates.stream()
                .map(CandidateProduct::getCurrency)
                .filter(currency -> currency != null && !budgetCurrency.equalsIgnoreCase(currency.trim()))
                .distinct()
                .forEach(currency -> LOG.warn(
                        "Candidate priced in {} compared against a {} budget without conversion; "
                                + "single-currency assumption may not hold",
                        currency.trim(), budgetCurrency));
    }

    private record Context(String category, Long ownedProductId, DevicePreference preference) {}
}
