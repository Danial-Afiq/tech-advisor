package com.springboot.backend.service;

import com.springboot.backend.dto.DeviceRequest;
import com.springboot.backend.dto.DeviceResponse;
import com.springboot.backend.exception.ResourceNotFoundException;
import com.springboot.backend.model.DevicePreference;
import com.springboot.backend.model.Product;
import com.springboot.backend.model.User;
import com.springboot.backend.model.UserDevice;
import com.springboot.backend.repository.DevicePreferenceRepository;
import com.springboot.backend.repository.ProductRepository;
import com.springboot.backend.repository.UserDeviceRepository;
import com.springboot.backend.repository.UserRepository;
import com.springboot.backend.recommendation.classification.Factors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.springboot.backend.exception.InvalidDeviceRequestException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DeviceService {

    private final UserDeviceRepository userDeviceRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final DevicePreferenceRepository devicePreferenceRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public DeviceService(
            UserDeviceRepository userDeviceRepository,
            UserRepository userRepository,
            ProductRepository productRepository,
            DevicePreferenceRepository devicePreferenceRepository,
            ApplicationEventPublisher eventPublisher) {

        this.userDeviceRepository = userDeviceRepository;
        this.userRepository = userRepository;
        this.productRepository = productRepository;
        this.devicePreferenceRepository = devicePreferenceRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public DeviceResponse createDevice(
            String email,
            DeviceRequest request) {

        User user = findUser(email);
        validateDeviceIdentity(request);
        validateJsonFields(request);

        Product product = findOptionalProduct(
                request.getProductId()
        );

        String customName = normaliseCustomName(
                request.getCustomName()
        );

        UserDevice device = new UserDevice(
                user.getId(),
                product,
                customName
        );

        applyEditableFields(device, request);

        UserDevice savedDevice =
                userDeviceRepository.save(device);

        DevicePreference preference =
                applyPreferences(savedDevice.getId(), request);

        eventPublisher.publishEvent(
                new DeviceInventoryChanged(savedDevice.getId(), DeviceInventoryChanged.Change.ADDED)
        );

        return new DeviceResponse(savedDevice, preference);
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> getCurrentDevices(
            String email) {

        User user = findUser(email);

        List<UserDevice> devices = userDeviceRepository
                .findAllByUserIdAndIsCurrentTrueOrderByCreatedAtDesc(
                        user.getId()
                );

        // One query for every device's preferences, not one per device.
        Map<Long, DevicePreference> preferences = devicePreferenceRepository
                .findAllById(
                        devices.stream().map(UserDevice::getId).toList()
                )
                .stream()
                .collect(Collectors.toMap(
                        DevicePreference::getUserDeviceId,
                        Function.identity()
                ));

        return devices.stream()
                .map(device -> new DeviceResponse(
                        device,
                        preferences.get(device.getId())
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public DeviceResponse getDevice(
            String email,
            Long deviceId) {

        User user = findUser(email);

        UserDevice device = findOwnedCurrentDevice(
                deviceId,
                user.getId()
        );

        return new DeviceResponse(
                device,
                findPreference(device.getId())
        );
    }

    @Transactional
    public DeviceResponse updateDevice(
            String email,
            Long deviceId,
            DeviceRequest request) {

        User user = findUser(email);
        validateDeviceIdentity(request);
        validateJsonFields(request);

        UserDevice device = findOwnedCurrentDevice(
                deviceId,
                user.getId()
        );

        Product product = findOptionalProduct(
                request.getProductId()
        );

        device.setProduct(product);
        device.setCustomName(
                normaliseCustomName(request.getCustomName())
        );

        applyEditableFields(device, request);

        UserDevice savedDevice =
                userDeviceRepository.save(device);

        DevicePreference preference =
                applyPreferences(savedDevice.getId(), request);

        eventPublisher.publishEvent(
                new DeviceInventoryChanged(savedDevice.getId(), DeviceInventoryChanged.Change.UPDATED)
        );

        return new DeviceResponse(savedDevice, preference);
    }

    @Transactional
    public void removeDevice(
            String email,
            Long deviceId) {

        User user = findUser(email);

        UserDevice device = findOwnedCurrentDevice(
                deviceId,
                user.getId()
        );

        device.setCurrent(false);
        userDeviceRepository.save(device);
    }

    private User findUser(String email) {

        return userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "User not found"
                        ));
    }

    private UserDevice findOwnedCurrentDevice(
            Long deviceId,
            Long userId) {

        return userDeviceRepository
                .findByIdAndUserIdAndIsCurrentTrue(
                        deviceId,
                        userId
                )
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Device not found"
                        ));
    }

    private Product findOptionalProduct(Long productId) {

        if (productId == null) {
            return null;
        }

        return productRepository.findById(productId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Product not found"
                        ));
    }

    private void validateDeviceIdentity(
            DeviceRequest request) {

        boolean hasProduct =
                request.getProductId() != null;

        boolean hasCustomName =
                request.getCustomName() != null
                        && !request.getCustomName()
                                .isBlank();

        if (!hasProduct && !hasCustomName) {
            throw new InvalidDeviceRequestException(
                    "A product ID or custom name is required"
            );
        }
    }

    private String normaliseCustomName(
            String customName) {

        if (customName == null) {
            return null;
        }

        String trimmedName = customName.trim();

        return trimmedName.isEmpty()
                ? null
                : trimmedName;
    }

    private void applyEditableFields(
            UserDevice device,
            DeviceRequest request) {

        device.setPurchaseDate(
                request.getPurchaseDate()
        );

        device.setCondition(
                request.getCondition()
        );

        device.setSatisfactionScore(
                request.getSatisfactionScore()
        );

        device.setUseCases(
                request.getUseCases() == null
                        ? "[]"
                        : request.getUseCases()
        );

        device.setSpecOverrides(
                request.getSpecOverrides() == null
                        ? "{}"
                        : request.getSpecOverrides()
        );
    }

    /**
     * Upserts preferences only after the device's ownership has been checked.
     * Omitted fields preserve saved values. The first save requires a budget;
     * later requests can update individual preferences without resending it.
     *
     * @return the device's preferences after the change, or null if it has none
     */
    private DevicePreference applyPreferences(
            Long deviceId,
            DeviceRequest request) {

        DevicePreference preference = findPreference(deviceId);
        boolean supplied = request.getBudget() != null || request.getCurrency() != null
                || request.getUpgradeUrgency() != null || request.getBrandFlexibility() != null
                || request.getPriorities() != null || request.getPainPoints() != null || request.getNotes() != null;
        if (!supplied) return preference;

        if (preference == null) {
            if (request.getBudget() == null) {
                throw new InvalidDeviceRequestException("A budget is required when first saving upgrade preferences");
            }
            preference = new DevicePreference(deviceId, request.getBudget(), "SGD");
        }

        if (request.getBudget() != null) preference.setBudget(request.getBudget());

        if (request.getCurrency() != null) {
            preference.setCurrency(request.getCurrency());
        }
        if (request.getUpgradeUrgency() != null) preference.setUpgradeUrgency(request.getUpgradeUrgency());
        if (request.getBrandFlexibility() != null) preference.setBrandFlexibility(request.getBrandFlexibility());
        if (request.getPriorities() != null) preference.setPriorities(request.getPriorities());
        if (request.getPainPoints() != null) preference.setPainPoints(request.getPainPoints());
        if (request.getNotes() != null) preference.setNotes(request.getNotes());

        devicePreferenceRepository.save(preference);
        return preference;
    }

    private DevicePreference findPreference(Long deviceId) {

        if (deviceId == null) {
            return null;
        }

        return devicePreferenceRepository
                .findById(deviceId)
                .orElse(null);
    }

    private void validateJsonFields(DeviceRequest request) {
        try {
            if (request.getUseCases() != null) {
                jsonMapper.readTree(request.getUseCases());
            }
            if (request.getSpecOverrides() != null) {
                jsonMapper.readTree(request.getSpecOverrides());
            }
            if (request.getPriorities() != null) {
                JsonNode priorities = jsonMapper.readTree(request.getPriorities());
                if (priorities == null || !priorities.isObject()) {
                    throw new InvalidDeviceRequestException("Priorities must be a JSON object of factor names and integer weights");
                }
                for (Map.Entry<String, JsonNode> entry : priorities.properties()) {
                    if (!Factors.isFactor(entry.getKey()) || !entry.getValue().isIntegralNumber()
                            || !entry.getValue().canConvertToInt()) {
                        throw new InvalidDeviceRequestException("Priorities must use supported factors and integer weights");
                    }
                }
            }
            if (request.getPainPoints() != null) {
                JsonNode painPoints = jsonMapper.readTree(request.getPainPoints());
                if (painPoints == null || !(painPoints.isString() || painPoints.isArray())) {
                    throw new InvalidDeviceRequestException("Pain points must be JSON-encoded text or an array of text");
                }
                if (painPoints.isArray()) {
                    for (JsonNode point : painPoints) {
                        if (!point.isString()) {
                            throw new InvalidDeviceRequestException("Each pain point must be text");
                        }
                    }
                }
            }
        } catch (tools.jackson.core.JacksonException exception) {
            throw new InvalidDeviceRequestException(
                    "Use cases, spec overrides, priorities and pain points must contain valid JSON"
            );
        }
    }
}
