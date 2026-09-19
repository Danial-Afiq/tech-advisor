package com.springboot.backend.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * What the user wants from one specific owned device. Preferences are per
 * device, not per user: someone may want very different things from a phone
 * than from a laptop. The primary key is the owning device's id.
 */
@Entity
@Table(name = "device_preferences")
public class DevicePreference {

    @Id
    @Column(name = "user_device_id")
    private Long userDeviceId;

    /** The budget ceiling. NOT NULL: shortlisting is meaningless without one. */
    @Column(nullable = false)
    private BigDecimal budget;

    /**
     * Persisted but not yet used for conversion - budget and price are
     * compared as bare numbers. See CandidatePruningService.
     */
    @Column(nullable = false)
    private String currency = "SGD";

    @Column(name = "upgrade_urgency")
    private String upgradeUrgency;

    @Column(name = "brand_flexibility")
    private String brandFlexibility;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String priorities = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pain_points", nullable = false)
    private String painPoints = "{}";

    private String notes;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected DevicePreference() {}

    public DevicePreference(Long userDeviceId, BigDecimal budget, String currency) {
        this.userDeviceId = userDeviceId;
        this.budget = budget;
        this.currency = currency;
    }

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public Long getUserDeviceId() { return userDeviceId; }
    public BigDecimal getBudget() { return budget; }
    public String getCurrency() { return currency; }
    public String getUpgradeUrgency() { return upgradeUrgency; }
    public String getBrandFlexibility() { return brandFlexibility; }
    public String getPriorities() { return priorities; }
    public String getPainPoints() { return painPoints; }
    public String getNotes() { return notes; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setBudget(BigDecimal budget) { this.budget = budget; }
    public void setCurrency(String currency) { this.currency = currency; }
    public void setUpgradeUrgency(String urgency) { this.upgradeUrgency = urgency; }
    public void setBrandFlexibility(String flexibility) { this.brandFlexibility = flexibility; }
    public void setPriorities(String priorities) { this.priorities = priorities; }
    public void setPainPoints(String painPoints) { this.painPoints = painPoints; }
    public void setNotes(String notes) { this.notes = notes; }
}
