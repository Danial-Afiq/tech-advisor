package com.springboot.backend.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One observed price for one product at one moment. Observations accumulate;
 * the candidate filter reads only the most recent one per product.
 */
@Entity
@Table(name = "price_history")
public class PriceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private String currency;

    private String source;

    /** The source's observation time, not our ingest time. */
    @Column(name = "observed_at", nullable = false)
    private OffsetDateTime observedAt;

    protected PriceHistory() {}

    public PriceHistory(Long productId, BigDecimal price, String currency,
                        String source, OffsetDateTime observedAt) {
        this.productId = productId;
        this.price = price;
        this.currency = currency;
        this.source = source;
        this.observedAt = observedAt;
    }

    public Long getId() { return id; }
    public Long getProductId() { return productId; }
    public BigDecimal getPrice() { return price; }
    public String getCurrency() { return currency; }
    public String getSource() { return source; }
    public OffsetDateTime getObservedAt() { return observedAt; }
}
