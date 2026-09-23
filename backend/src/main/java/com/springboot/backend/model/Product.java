package com.springboot.backend.model;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A catalogue entry. Identity is (brand, model_name); everything the
 * recommendation engine compares hangs off this row.
 */
@Entity
@Table(name = "products")
public class Product {

    /** Ingestion resolved a brand confidently; eligible for shortlisting. */
    public static final String STATUS_VERIFIED = "VERIFIED";
    /** Brand could not be resolved. Retained with its history, never shortlisted. */
    public static final String STATUS_UNVERIFIED = "UNVERIFIED";

    public static final String CATEGORY_SMARTPHONE = "SMARTPHONE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String brand;

    @Column(name = "model_name", nullable = false)
    private String modelName;

    @Column(nullable = false)
    private String category;

    /** Nullable on purpose: the maturity gate must cope with an unknown release date. */
    @Column(name = "release_date")
    private LocalDate releaseDate;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Product() {}

    public Product(String brand, String modelName, String category, String status) {
        this.brand = brand;
        this.modelName = modelName;
        this.category = category;
        this.status = status;
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
    public String getBrand() { return brand; }
    public String getModelName() { return modelName; }
    public String getCategory() { return category; }
    public LocalDate getReleaseDate() { return releaseDate; }
    public String getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setBrand(String brand) { this.brand = brand; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public void setCategory(String category) { this.category = category; }
    public void setReleaseDate(LocalDate releaseDate) { this.releaseDate = releaseDate; }
    public void setStatus(String status) { this.status = status; }
}
