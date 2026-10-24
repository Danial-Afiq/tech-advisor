package com.springboot.backend.dto;

import com.springboot.backend.model.DevicePreference;
import com.springboot.backend.model.Product;
import com.springboot.backend.model.UserDevice;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Schema(description = "An owned device belonging to the authenticated user.")
public class DeviceResponse {

    @Schema(description = "Owned-device identifier.", example = "27", accessMode = Schema.AccessMode.READ_ONLY)
    private final Long id;

    @Schema(description = "Linked catalogue product identifier, if present.", example = "812", accessMode = Schema.AccessMode.READ_ONLY)
    private final Long productId;

    @Schema(description = "Catalogue brand, if linked.", example = "Samsung", accessMode = Schema.AccessMode.READ_ONLY)
    private final String productBrand;

    @Schema(description = "Catalogue model name, if linked.", example = "Galaxy S22", accessMode = Schema.AccessMode.READ_ONLY)
    private final String productModelName;

    @Schema(description = "Catalogue category of the linked product (e.g. SMARTPHONE), if linked.", example = "SMARTPHONE", accessMode = Schema.AccessMode.READ_ONLY)
    private final String productCategory;

    @Schema(description = "User-provided device name.", example = "My Galaxy S22", accessMode = Schema.AccessMode.READ_ONLY)
    private final String customName;

    @Schema(description = "Purchase date.", example = "2024-02-15", accessMode = Schema.AccessMode.READ_ONLY)
    private final LocalDate purchaseDate;

    @Schema(description = "User-described physical condition.", example = "FAIR", accessMode = Schema.AccessMode.READ_ONLY)
    private final String condition;

    @Schema(description = "Current satisfaction from 0 to 100.", example = "45", accessMode = Schema.AccessMode.READ_ONLY)
    private final Integer satisfactionScore;

    @Schema(description = "JSON-encoded array of use cases.", example = "[\"photography\",\"gaming\"]", accessMode = Schema.AccessMode.READ_ONLY)
    private final String useCases;

    @Schema(description = "JSON-encoded specification overrides.", example = "{\"storage_gb\":256}", accessMode = Schema.AccessMode.READ_ONLY)
    private final String specOverrides;

    @Schema(
            description = "Device-specific upgrade budget from device_preferences; null when none has been set, "
                    + "in which case the device is not evaluated for recommendations.",
            example = "1200.00",
            accessMode = Schema.AccessMode.READ_ONLY)
    private final BigDecimal budget;

    @Schema(
            description = "ISO 4217 currency of the budget; null when no budget has been set.",
            example = "SGD",
            accessMode = Schema.AccessMode.READ_ONLY)
    private final String currency;

    @Schema(description = "Saved upgrade urgency, or null if unset.", example = "NOT_URGENT", accessMode = Schema.AccessMode.READ_ONLY)
    private final String upgradeUrgency;

    @Schema(description = "Saved brand flexibility, or null if unset.", example = "FLEXIBLE", accessMode = Schema.AccessMode.READ_ONLY)
    private final String brandFlexibility;

    @Schema(description = "JSON-encoded factor-to-integer priority weights; null when there is no preferences row.",
            example = "{\"battery\":5,\"camera\":4}", accessMode = Schema.AccessMode.READ_ONLY)
    private final String priorities;

    @Schema(description = "JSON-encoded pain points; null when there is no preferences row.",
            example = "\"Battery drains too quickly\"", accessMode = Schema.AccessMode.READ_ONLY)
    private final String painPoints;

    @Schema(description = "Optional saved notes for replacing this device.", accessMode = Schema.AccessMode.READ_ONLY)
    private final String notes;

    @Schema(description = "Whether this device remains active on the account.", example = "true", accessMode = Schema.AccessMode.READ_ONLY)
    private final boolean current;

    @Schema(description = "Creation timestamp.", accessMode = Schema.AccessMode.READ_ONLY)
    private final OffsetDateTime createdAt;

    @Schema(description = "Last update timestamp.", accessMode = Schema.AccessMode.READ_ONLY)
    private final OffsetDateTime updatedAt;

    /**
     * @param preference the device's preferences row, or null when it has none
     */
    public DeviceResponse(UserDevice device, DevicePreference preference) {

        Product product = device.getProduct();

        this.id = device.getId();
        this.productId =
                product == null ? null : product.getId();
        this.productBrand =
                product == null ? null : product.getBrand();
        this.productModelName =
                product == null ? null : product.getModelName();
        this.productCategory =
                product == null ? null : product.getCategory();

        this.customName = device.getCustomName();
        this.purchaseDate = device.getPurchaseDate();
        this.condition = device.getCondition();
        this.satisfactionScore = device.getSatisfactionScore();
        this.useCases = device.getUseCases();
        this.specOverrides = device.getSpecOverrides();
        this.budget =
                preference == null ? null : preference.getBudget();
        this.currency =
                preference == null ? null : preference.getCurrency();
        this.upgradeUrgency = preference == null ? null : preference.getUpgradeUrgency();
        this.brandFlexibility = preference == null ? null : preference.getBrandFlexibility();
        this.priorities = preference == null ? null : preference.getPriorities();
        this.painPoints = preference == null ? null : preference.getPainPoints();
        this.notes = preference == null ? null : preference.getNotes();
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

    public String getProductCategory() {
        return productCategory;
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

    public BigDecimal getBudget() {
        return budget;
    }

    public String getCurrency() {
        return currency;
    }

    public String getUpgradeUrgency() { return upgradeUrgency; }
    public String getBrandFlexibility() { return brandFlexibility; }
    public String getPriorities() { return priorities; }
    public String getPainPoints() { return painPoints; }
    public String getNotes() { return notes; }

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
