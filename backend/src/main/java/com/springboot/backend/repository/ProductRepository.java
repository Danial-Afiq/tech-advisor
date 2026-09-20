package com.springboot.backend.repository;

import com.springboot.backend.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByBrandAndModelName(String brand, String modelName);

    /**
     * Deterministic candidate shortlisting: same category, at or under the
     * budget ceiling, excluding the device the user already owns.
     *
     * <p>Native rather than JPQL because {@code DISTINCT ON} is what lets
     * PostgreSQL satisfy "latest price per product" from
     * {@code idx_price_history_lookup} instead of aggregating the whole table.
     *
     * <p>Three details that are load-bearing:
     * <ul>
     *   <li>{@code ORDER BY ... id DESC} breaks ties between two observations
     *       sharing an {@code observed_at}. Without it the chosen price is
     *       arbitrary, and this step has to be reproducible.</li>
     *   <li>{@code INNER JOIN} excludes products with no price history at all:
     *       affordability cannot be verified, so it is not a candidate.</li>
     *   <li>{@code status = 'VERIFIED'} keeps products whose brand ingestion
     *       could not resolve out of user-facing results.</li>
     * </ul>
     */
    @Query(value = """
            SELECT p.id         AS productId,
                   p.brand      AS brand,
                   p.model_name AS modelName,
                   ph.price     AS latestPrice,
                   ph.currency  AS currency
            FROM products p
            INNER JOIN (
                SELECT DISTINCT ON (product_id)
                       product_id, price, currency
                FROM price_history
                ORDER BY product_id, observed_at DESC, id DESC
            ) ph ON p.id = ph.product_id
            WHERE p.category = :category
              AND p.status   = 'VERIFIED'
              AND ph.price  <= :budgetCeiling
              AND p.id      <> :currentProductId
            ORDER BY ph.price ASC, p.id ASC
            """, nativeQuery = true)
    List<CandidateProduct> findCompatibleCandidates(
            @Param("category") String category,
            @Param("budgetCeiling") BigDecimal budgetCeiling,
            @Param("currentProductId") Long currentProductId);
}
