package com.springboot.backend.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Something a user owns. The catalogue link is optional, so a device may exist
 * without a product row behind it; candidate shortlisting needs the owned
 * product's category and therefore skips devices that have no link.
 */
@Entity
@Table(name = "user_devices")
public class UserDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Nullable catalogue link: a user may own something we do not stock. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(name = "custom_name")
    private String customName;

    @Column(name = "purchase_date")
    private LocalDate purchaseDate;

    private String condition;

    @Column(name = "satisfaction_score")
    private Integer satisfactionScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "use_cases", nullable = false)
    private String useCases = "[]";

    @Column(name = "is_current", nullable = false)
    private boolean isCurrent = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spec_overrides", nullable = false)
    private String specOverrides = "{}";

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected UserDevice() {}

    public UserDevice(Long userId, Product product, String customName) {
        this.userId = userId;
        this.product = product;
        this.customName = customName;
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

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Product getProduct() { return product; }
    public String getCustomName() { return customName; }
    public LocalDate getPurchaseDate() { return purchaseDate; }
    public String getCondition() { return condition; }
    public Integer getSatisfactionScore() { return satisfactionScore; }
    public String getUseCases() { return useCases; }
    public boolean isCurrent() { return isCurrent; }
    public String getSpecOverrides() { return specOverrides; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setProduct(Product product) { this.product = product; }
    public void setCustomName(String customName) { this.customName = customName; }
    public void setPurchaseDate(LocalDate purchaseDate) { this.purchaseDate = purchaseDate; }
    public void setCondition(String condition) { this.condition = condition; }
    public void setSatisfactionScore(Integer score) { this.satisfactionScore = score; }
    public void setUseCases(String useCases) { this.useCases = useCases; }
    public void setCurrent(boolean current) { this.isCurrent = current; }
    public void setSpecOverrides(String specOverrides) { this.specOverrides = specOverrides; }
}
