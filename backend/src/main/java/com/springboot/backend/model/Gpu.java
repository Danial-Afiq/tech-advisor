package com.springboot.backend.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * The graphics card subtype row for one product (AGENTS.md §14.6).
 *
 * <p>Like {@link Phone}, these are deterministic facts Java compares directly,
 * and every column except the primary key is nullable: a null means "not
 * known", never "zero".
 *
 * <p>Only the columns ticket 1.3 ingestion writes are mapped. The V6 columns
 * {@code clock_speeds} and {@code tgp} are superseded by the base/boost clock
 * and TDP columns from V13, and the remaining V6 columns (core count, memory
 * speed, bandwidth, fill rates) have no source yet.
 *
 * <p>Ingestion writes this table; nothing else does.
 */
@Entity
@Table(name = "gpu")
public class Gpu {

    /** Shares the products primary key - this is a subtype, not a child row. */
    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "base_clock_mhz")
    private Integer baseClockMhz;

    @Column(name = "boost_clock_mhz")
    private Integer boostClockMhz;

    /** Video memory in GB. */
    @Column(name = "vram")
    private BigDecimal vramGb;

    @Column(name = "memory_type")
    private String memoryType;

    /** Memory bus width in bits. */
    @Column(name = "bus_width")
    private Integer memoryBusBits;

    @Column(name = "tdp_watts")
    private Integer tdpWatts;

    private String architecture;

    private String source;

    @Column(name = "source_url")
    private String sourceUrl;

    @Column(name = "fetched_at")
    private OffsetDateTime fetchedAt;

    protected Gpu() {}

    public Long getProductId() { return productId; }
    public Integer getBaseClockMhz() { return baseClockMhz; }
    public Integer getBoostClockMhz() { return boostClockMhz; }
    public BigDecimal getVramGb() { return vramGb; }
    public String getMemoryType() { return memoryType; }
    public Integer getMemoryBusBits() { return memoryBusBits; }
    public Integer getTdpWatts() { return tdpWatts; }
    public String getArchitecture() { return architecture; }
    public String getSource() { return source; }
    public String getSourceUrl() { return sourceUrl; }
    public OffsetDateTime getFetchedAt() { return fetchedAt; }
}
