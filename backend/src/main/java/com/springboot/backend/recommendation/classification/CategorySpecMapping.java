package com.springboot.backend.recommendation.classification;

import java.util.Optional;

/**
 * Connects one product category to the recommendation layer.
 *
 * <p>Adding a category means adding one {@code @Component} implementing this.
 * {@link CategorySpecRegistry} picks it up; nothing else in the scorer changes.
 *
 * @param <T> the category's spec entity
 */
public interface CategorySpecMapping<T> {

    /** The {@code products.category} value this mapping serves, such as {@code SMARTPHONE}. */
    String category();

    SpecRules<T> rules();

    /** The spec sheet for one product, or empty when none is recorded. */
    Optional<T> load(Long productId);
}
