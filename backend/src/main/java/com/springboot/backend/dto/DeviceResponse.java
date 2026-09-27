package com.springboot.backend.dto;

import com.springboot.backend.model.Product;
import com.springboot.backend.model.UserDevice;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public class DeviceResponse {

    private final Long id;
    private final Long productId;
    private final String productBrand;
    private final String productModelName;
    private final String customName;
    private final LocalDate purchaseDate;
    private final String condition;
    private final Integer satisfactionScore;
    private final String useCases;
    private final String specOverrides;
    private final boolean current;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;

    public DeviceResponse(UserDevice device) {

        Product product = device.getProduct();

        this.id = device.getId();
        this.productId =
                product == null ? null : product.getId();
        this.productBrand =
                product == null ? null : product.getBrand();
        this.productModelName =
                product == null ? null : product.getModelName();

        this.customName = device.getCustomName();
        this.purchaseDate = device.getPurchaseDate();
        this.condition = device.getCondition();
        this.satisfactionScore = device.getSatisfactionScore();
        this.useCases = device.getUseCases();
        this.specOverrides = device.getSpecOverrides();
        this.current = device.isCurrent();
        this.createdAt = device.getCreatedAt();
        this.updatedAt = device.getUpdatedAt();
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public String getProductBrand() {
        return productBrand;
    }

    public String getProductModelName() {
        return productModelName;
    }

    public String getCustomName() {
        return customName;
    }

    public LocalDate getPurchaseDate() {
        return purchaseDate;
    }

    public String getCondition() {
        return condition;
    }

    public Integer getSatisfactionScore() {
        return satisfactionScore;
    }

    public String getUseCases() {
        return useCases;
    }

    public String getSpecOverrides() {
        return specOverrides;
    }

    public boolean isCurrent() {
        return current;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}