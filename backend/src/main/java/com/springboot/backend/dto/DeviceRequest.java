package com.springboot.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

@Schema(description = "Owned-device fields. Supply at least one of productId or customName.")
public class DeviceRequest {

    @Schema(description = "Verified catalogue product identifier, when the device is known.", example = "812")
    @Positive(message = "Product ID must be positive")
    private Long productId;

    @Schema(description = "User-provided device name, required when productId is omitted.", example = "My Galaxy S22", maxLength = 100)
    @Size(
            max = 100,
            message = "Custom name must not exceed 100 characters"
    )
    private String customName;

    @Schema(description = "Purchase date; future dates are rejected.", example = "2024-02-15")
    @PastOrPresent(
            message = "Purchase date cannot be in the future"
    )
    private LocalDate purchaseDate;

    @Schema(description = "User-described physical condition.", example = "FAIR", maxLength = 50)
    @Size(
            max = 50,
            message = "Condition must not exceed 50 characters"
    )
    private String condition;

    @Schema(description = "Current satisfaction from 0 to 100.", example = "45", minimum = "0", maximum = "100")
    @Min(
            value = 0,
            message = "Satisfaction score must be at least 0"
    )
    @Max(
            value = 100,
            message = "Satisfaction score must not exceed 100"
    )
    private Integer satisfactionScore;

    @Schema(
            description = "JSON-encoded array of use cases.",
            example = "[\"photography\",\"gaming\"]",
            defaultValue = "[]")
    private String useCases = "[]";

    @Schema(
            description = "JSON-encoded object containing user-supplied specification overrides.",
            example = "{\"storage_gb\":256}",
            defaultValue = "{}")
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
