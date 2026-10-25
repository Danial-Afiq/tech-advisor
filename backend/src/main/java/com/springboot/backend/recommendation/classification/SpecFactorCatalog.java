package com.springboot.backend.recommendation.classification;

import java.math.BigDecimal;

/**
 * Scoring constants shared by every category. Per-category column rules live in
 * the {@link CategorySpecMapping} implementations, such as
 * {@link PhoneSpecMapping}.
 */
public final class SpecFactorCatalog {

    /** Synthetic spec keys that do not come from a spec table column. */
    public static final String BENCHMARK_SPEC = "benchmark_score";
    public static final String PRICE_SPEC = "price";

    /** Uplift treated as a full-strength performance win. */
    public static final double BENCHMARK_IMPROVEMENT_CAP = 60.0;

    /** Budget headroom, as a percentage of budget, treated as full value. */
    public static final double PRICE_IMPROVEMENT_CAP = 30.0;

    static Double toDouble(Number value) {
        if (value == null) return null;
        if (value instanceof BigDecimal decimal) return decimal.doubleValue();
        return value.doubleValue();
    }

    private SpecFactorCatalog() {}
}
