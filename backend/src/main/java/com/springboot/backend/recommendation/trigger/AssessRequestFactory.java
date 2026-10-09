package com.springboot.backend.recommendation.trigger;

import com.springboot.backend.exception.ResourceNotFoundException;
import com.springboot.backend.model.DevicePreference;
import com.springboot.backend.model.Product;
import com.springboot.backend.model.UserDevice;
import com.springboot.backend.recommendation.AssessRequest;
import com.springboot.backend.recommendation.AssessVocabulary;
import com.springboot.backend.recommendation.CandidateEvaluation;
import com.springboot.backend.recommendation.classification.Factors;
import com.springboot.backend.recommendation.classification.UpgradeClassification;
import com.springboot.backend.repository.DevicePreferenceRepository;
import com.springboot.backend.repository.ProductRepository;
import com.springboot.backend.repository.UserDeviceRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the {@code POST /assess} request (AGENTS.md §9) for one classified pair
 * from what the database holds.
 *
 * <p>The AI contract requires context the database often does not have yet:
 * condition, satisfaction, purchase date, urgency, brand flexibility and the
 * candidate's release date. Rather than invent any of it (§0.2), a pair missing
 * something comes back as {@link Built#missing()} naming each gap, and the caller
 * skips the model call. The deterministic row already persisted stands.
 *
 * <p>Every number is finished here or by Channel A (§8.2): ages are computed from
 * dates, and {@code computed}/{@code analysis} come straight from the
 * classification.
 */
@Component
public class AssessRequestFactory {

    private final UserDeviceRepository devices;
    private final DevicePreferenceRepository preferences;
    private final ProductRepository products;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    public AssessRequestFactory(
            UserDeviceRepository devices,
            DevicePreferenceRepository preferences,
            ProductRepository products,
            Clock clock) {

        this.devices = devices;
        this.preferences = preferences;
        this.products = products;
        this.clock = clock;
    }

    /**
     * @param triggerEvent the market event behind this run, or {@code null}
     */
    @Transactional(readOnly = true)
    public Built build(
            Long userDeviceId, CandidateEvaluation.Classified classified, AssessRequest.TriggerEvent triggerEvent) {

        UserDevice device = devices.findById(userDeviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Owned device " + userDeviceId + " not found"));
        DevicePreference preference = preferences.findById(userDeviceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Upgrade preferences not configured for device " + userDeviceId));
        Long candidateId = classified.candidate().getProductId();
        Product candidate = products.findById(candidateId)
                .orElseThrow(() -> new ResourceNotFoundException("Candidate product " + candidateId + " not found"));

        List<String> missing = new ArrayList<>();
        LocalDate today = LocalDate.now(clock);

        String condition = closedValue(device.getCondition(), AssessVocabulary.CONDITIONS, "user_devices.condition", missing);
        if (device.getSatisfactionScore() == null) missing.add("user_devices.satisfaction_score");
        if (device.getPurchaseDate() == null) missing.add("user_devices.purchase_date");
        String urgency = closedValue(
                preference.getUpgradeUrgency(), AssessVocabulary.UPGRADE_URGENCIES,
                "device_preferences.upgrade_urgency", missing);
        String flexibility = closedValue(
                preference.getBrandFlexibility(), AssessVocabulary.BRAND_FLEXIBILITIES,
                "device_preferences.brand_flexibility", missing);
        Map<String, Integer> priorities = priorities(preference.getPriorities(), missing);
        if (candidate.getReleaseDate() == null) missing.add("products.release_date (candidate " + candidateId + ")");

        if (!missing.isEmpty()) {
            return new Built(null, missing);
        }

        AssessRequest.OwnedDevice owned = new AssessRequest.OwnedDevice(
                deviceName(device),
                (int) Math.max(0, ChronoUnit.MONTHS.between(device.getPurchaseDate(), today)),
                condition,
                device.getSatisfactionScore(),
                useCases(device.getUseCases()));

        AssessRequest.Preferences prefs = new AssessRequest.Preferences(
                preference.getBudget().doubleValue(),
                preference.getCurrency(),
                urgency,
                flexibility,
                priorities,
                painPoints(preference.getPainPoints()),
                preference.getNotes());

        AssessRequest.Candidate candidateBlock = new AssessRequest.Candidate(
                candidateId,
                candidate.getBrand() + " " + candidate.getModelName(),
                candidate.getReleaseDate(),
                (int) ChronoUnit.DAYS.between(candidate.getReleaseDate(), today));

        UpgradeClassification classification = classified.classification();
        AssessRequest request = new AssessRequest(
                null,
                new AssessRequest.UserContext(owned, prefs),
                candidateBlock,
                classification.toComputed(triggerEvent),
                classification.toAnalysis(),
                null);
        return new Built(request, List.of());
    }

    private static String closedValue(String raw, List<String> allowed, String column, List<String> missing) {
        String value = raw == null ? null : raw.trim().toUpperCase(Locale.ROOT);
        if (value == null || !allowed.contains(value)) {
            missing.add(column);
            return null;
        }
        return value;
    }

    /** Priorities may only use the closed factor vocabulary, with integer weights. */
    private Map<String, Integer> priorities(String raw, List<String> missing) {
        Map<String, Integer> result = new LinkedHashMap<>();
        JsonNode node = readTree(raw);
        if (node == null || node.isNull()) {
            return result;
        }
        if (!node.isObject()) {
            missing.add("device_preferences.priorities (not a JSON object)");
            return result;
        }
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (!Factors.isFactor(entry.getKey()) || !entry.getValue().isIntegralNumber()) {
                missing.add("device_preferences.priorities (invalid entry '" + entry.getKey() + "')");
                continue;
            }
            result.put(entry.getKey(), entry.getValue().intValue());
        }
        return result;
    }

    private List<String> useCases(String raw) {
        JsonNode node = readTree(raw);
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> result.add(item.isString() ? item.stringValue() : item.toString()));
        }
        return result;
    }

    /**
     * {@code pain_points} is JSONB, the wire field is text. A string or a list of
     * strings is passed through as prose; an empty value is no pain points.
     */
    private String painPoints(String raw) {
        JsonNode node = readTree(raw);
        if (node == null || node.isNull() || node.isEmpty() && node.isContainer()) {
            return null;
        }
        if (node.isString()) {
            return node.stringValue().isBlank() ? null : node.stringValue();
        }
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            node.forEach(item -> parts.add(item.isString() ? item.stringValue() : item.toString()));
            return String.join("; ", parts);
        }
        return node.toString();
    }

    private JsonNode readTree(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return json.readTree(raw);
    }

    private static String deviceName(UserDevice device) {
        if (device.getCustomName() != null && !device.getCustomName().isBlank()) {
            return device.getCustomName();
        }
        Product owned = device.getProduct();
        return owned == null ? "Current device" : owned.getBrand() + " " + owned.getModelName();
    }

    /**
     * @param request the request to send, or {@code null} when anything is missing
     * @param missing each piece of required context the database does not hold
     */
    public record Built(AssessRequest request, List<String> missing) {

        public Built {
            missing = List.copyOf(missing);
        }

        public boolean complete() {
            return missing.isEmpty();
        }
    }
}
