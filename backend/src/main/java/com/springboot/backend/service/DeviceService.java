package com.springboot.backend.service;

import com.springboot.backend.dto.DeviceRequest;
import com.springboot.backend.dto.DeviceResponse;
import com.springboot.backend.exception.ResourceNotFoundException;
import com.springboot.backend.model.Product;
import com.springboot.backend.model.User;
import com.springboot.backend.model.UserDevice;
import com.springboot.backend.repository.ProductRepository;
import com.springboot.backend.repository.UserDeviceRepository;
import com.springboot.backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.springboot.backend.exception.InvalidDeviceRequestException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

@Service
public class DeviceService {

    private final UserDeviceRepository userDeviceRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public DeviceService(
            UserDeviceRepository userDeviceRepository,
            UserRepository userRepository,
            ProductRepository productRepository) {

        this.userDeviceRepository = userDeviceRepository;
        this.userRepository = userRepository;
        this.productRepository = productRepository;
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

        return new DeviceResponse(savedDevice);
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> getCurrentDevices(
            String email) {

        User user = findUser(email);

        return userDeviceRepository
                .findAllByUserIdAndIsCurrentTrueOrderByCreatedAtDesc(
                        user.getId()
                )
                .stream()
                .map(DeviceResponse::new)
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

        return new DeviceResponse(device);
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

        return new DeviceResponse(savedDevice);
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

    private void validateJsonFields(DeviceRequest request) {
        try {
                if (request.getUseCases() != null) {
                jsonMapper.readTree(request.getUseCases());
                }
                if (request.getSpecOverrides() != null) {
                jsonMapper.readTree(request.getSpecOverrides());
                }
        } catch (tools.jackson.core.JacksonException exception) {
                throw new InvalidDeviceRequestException(
                        "Use cases and spec overrides must contain valid JSON"
                );
        }
        }
}