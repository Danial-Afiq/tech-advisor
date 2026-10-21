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

    Optional<Product> findByBrandIgnoreCaseAndModelNameIgnoreCase(String brand, String modelName);

    List<Product> findAllByCategoryOrderByBrandAscModelNameAsc(String category);

    /**
     * Latest price per product in a category, for the catalogue list. Same
     * selection as candidate shortlisting and the dashboard's {@code latestPrice}:
     * newest {@code observed_at}, ties broken by {@code id DESC}. Products with
     * no price history are absent.
     */
    @Query(value = """
            SELECT DISTINCT ON (ph.product_id)
                   ph.product_id AS productId,
                   ph.price      AS price,
                   ph.currency   AS currency
            FROM price_history ph
            JOIN products p ON p.id = ph.product_id
            WHERE p.category = :category
            ORDER BY ph.product_id, ph.observed_at DESC, ph.id DESC
            """, nativeQuery = true)
    List<LatestPrice> findLatestPrices(@Param("category") String category);

    /** {@link #findLatestPrices(String)} for one product. */
    @Query(value = """
            SELECT product_id AS productId, price, currency
            FROM price_history
            WHERE product_id = :productId
            ORDER BY observed_at DESC, id DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<LatestPrice> findLatestPrice(@Param("productId") Long productId);

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
