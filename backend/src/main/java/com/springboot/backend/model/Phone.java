package com.springboot.backend.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * The smartphone subtype row for one product (AGENTS.md §14.5).
 *
 * <p>These are deterministic facts, and the upgrade classifier compares them
 * directly - Java owns every spec delta, never the model (§8.2).
 *
 * <p>Every column except the primary key is nullable in V6, and that is load
 * bearing rather than sloppy: ingestion sources rarely publish a complete spec
 * sheet. A null means "not known", never "zero", so scoring must skip the spec
 * and shrink its coverage denominator instead of reading the absence as a
 * regression.
 *
 * <p>Read-only by design. Ingestion writes this table; the recommendation path
 * only compares it.
 */
@Entity
@Table(name = "phone")
public class Phone {

    /** Shares the products primary key - this is a subtype, not a child row. */
    @Id
    @Column(name = "product_id")
    private Long productId;

    private String chipset;

    @Column(name = "ram_gb")
    private Integer ramGb;

    @Column(name = "cpu_ghz")
    private BigDecimal cpuGhz;

    @Column(name = "storage_gb")
    private Integer storageGb;

    @Column(name = "battery_mah")
    private Integer batteryMah;

    @Column(name = "wired_charging_watts")
    private Integer wiredChargingWatts;

    @Column(name = "wireless_charging_watts")
    private Integer wirelessChargingWatts;

    @Column(name = "display_size_inches")
    private BigDecimal displaySizeInches;

    @Column(name = "refresh_rate_hz")
    private Integer refreshRateHz;

    @Column(name = "weight_g")
    private Integer weightG;

    @Column(name = "camera_specs")
    private String cameraSpecs;

    @Column(name = "pixel_density")
    private Integer pixelDensity;

    @Column(name = "ip_rating")
    private String ipRating;

    private String os;

    @Column(name = "software_support_years")
    private BigDecimal softwareSupportYears;

    protected Phone() {}

    /**
     * Builds a spec sheet field by field.
     *
     * <p>Sixteen mostly-null columns make a positional constructor unreadable
     * and a row of setters would imply this entity is writable, which it is not
     * - ingestion owns the table. The builder gives seeds and tests a way to
     * state only the specs they care about.
     */
    public static Builder builder(Long productId) {
        return new Builder(productId);
    }

    public static final class Builder {
        private final Phone phone = new Phone();

        private Builder(Long productId) {
            phone.productId = productId;
        }

        public Builder chipset(String v) { phone.chipset = v; return this; }
        public Builder ramGb(Integer v) { phone.ramGb = v; return this; }
        public Builder cpuGhz(BigDecimal v) { phone.cpuGhz = v; return this; }
        public Builder storageGb(Integer v) { phone.storageGb = v; return this; }
        public Builder batteryMah(Integer v) { phone.batteryMah = v; return this; }
        public Builder wiredChargingWatts(Integer v) { phone.wiredChargingWatts = v; return this; }
        public Builder wirelessChargingWatts(Integer v) { phone.wirelessChargingWatts = v; return this; }
        public Builder displaySizeInches(BigDecimal v) { phone.displaySizeInches = v; return this; }
        public Builder refreshRateHz(Integer v) { phone.refreshRateHz = v; return this; }
        public Builder weightG(Integer v) { phone.weightG = v; return this; }
        public Builder cameraSpecs(String v) { phone.cameraSpecs = v; return this; }
        public Builder pixelDensity(Integer v) { phone.pixelDensity = v; return this; }
        public Builder ipRating(String v) { phone.ipRating = v; return this; }
        public Builder os(String v) { phone.os = v; return this; }
        public Builder softwareSupportYears(BigDecimal v) { phone.softwareSupportYears = v; return this; }

        public Phone build() { return phone; }
    }

    public Long getProductId() { return productId; }
    public String getChipset() { return chipset; }
    public Integer getRamGb() { return ramGb; }
    public BigDecimal getCpuGhz() { return cpuGhz; }
    public Integer getStorageGb() { return storageGb; }
    public Integer getBatteryMah() { return batteryMah; }
    public Integer getWiredChargingWatts() { return wiredChargingWatts; }
    public Integer getWirelessChargingWatts() { return wirelessChargingWatts; }
    public BigDecimal getDisplaySizeInches() { return displaySizeInches; }
    public Integer getRefreshRateHz() { return refreshRateHz; }
    public Integer getWeightG() { return weightG; }
    public String getCameraSpecs() { return cameraSpecs; }
    public Integer getPixelDensity() { return pixelDensity; }
    public String getIpRating() { return ipRating; }
    public String getOs() { return os; }
    public BigDecimal getSoftwareSupportYears() { return softwareSupportYears; }
}
