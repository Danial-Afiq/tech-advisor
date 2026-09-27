package com.springboot.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public class DeviceRequest {

    @Positive(message = "Product ID must be positive")
    private Long productId;

    @Size(
            max = 100,
            message = "Custom name must not exceed 100 characters"
    )
    private String customName;

    @PastOrPresent(
            message = "Purchase date cannot be in the future"
    )
    private LocalDate purchaseDate;

    @Size(
            max = 50,
            message = "Condition must not exceed 50 characters"
    )
    private String condition;

    @Min(
            value = 0,
            message = "Satisfaction score must be at least 0"
    )
    @Max(
            value = 100,
            message = "Satisfaction score must not exceed 100"
    )
    private Integer satisfactionScore;

    private String useCases = "[]";

    private String specOverrides = "{}";

    public Long getProductId() {
        return productId;
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

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public void setCustomName(String customName) {
        this.customName = customName;
    }

    public void setPurchaseDate(LocalDate purchaseDate) {
        this.purchaseDate = purchaseDate;
    }

    public void setCondition(String condition) {
        this.condition = condition;
    }

    public void setSatisfactionScore(Integer satisfactionScore) {
        this.satisfactionScore = satisfactionScore;
    }

    public void setUseCases(String useCases) {
        this.useCases = useCases;
    }

    public void setSpecOverrides(String specOverrides) {
        this.specOverrides = specOverrides;
    }
}