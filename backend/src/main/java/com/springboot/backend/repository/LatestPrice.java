package com.springboot.backend.repository;

import java.math.BigDecimal;

/**
 * A product's latest {@code price_history} observation, as selected by
 * {@link ProductRepository#findLatestPrices(String)}.
 */
public interface LatestPrice {
    Long getProductId();
    BigDecimal getPrice();
    String getCurrency();
}
