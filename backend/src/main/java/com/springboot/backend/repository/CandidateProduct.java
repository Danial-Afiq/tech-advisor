package com.springboot.backend.repository;

import java.math.BigDecimal;

/**
 * A product being considered by deterministic shortlisting. Repository
 * projections carry the stored latest price; CandidatePruningService returns
 * the same shape with price and currency normalized to the device budget.
 *
 * <p>The price is carried deliberately. Downstream scoring needs price-vs-budget
 * as a finished number, and re-reading it per candidate would repeat work this
 * query has already done.
 *
 * <p>Spring Data binds these getters to the column aliases in
 * {@link ProductRepository}'s native query, so the alias names and the getter
 * names must stay in step.
 */
public interface CandidateProduct {
    Long getProductId();
    String getBrand();
    String getModelName();
    BigDecimal getLatestPrice();
    String getCurrency();
}
