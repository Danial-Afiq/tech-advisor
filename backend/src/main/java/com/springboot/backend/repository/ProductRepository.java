package com.springboot.backend.repository;

import com.springboot.backend.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface ProductRepository
        extends JpaRepository<Product, Long> {

    Optional<Product> findByBrandAndModelName(
            String brand,
            String modelName
    );

    Optional<Product>
            findByBrandIgnoreCaseAndModelNameIgnoreCase(
                    String brand,
                    String modelName
            );

    List<Product> findAllByCategoryOrderByBrandAscModelNameAsc(
            String category
    );

    /**
     * Latest price per product in a category, for the catalogue list.
     * Uses the newest observed_at, with id DESC to break ties.
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
    List<LatestPrice> findLatestPrices(
            @Param("category") String category
    );

    /**
     * Returns the latest recorded price for one product.
     */
    @Query(value = """
            SELECT product_id AS productId, price, currency
            FROM price_history
            WHERE product_id = :productId
            ORDER BY observed_at DESC, id DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<LatestPrice> findLatestPrice(
            @Param("productId") Long productId
    );

    /**
     * Deterministic candidate shortlisting: same category, verified,
     * affordable, not the currently owned product, and not dismissed
     * by this user during an active cooldown.
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
              AND p.status = 'VERIFIED'
              AND ph.price <= :budgetCeiling
              AND p.id <> :currentProductId
              AND NOT EXISTS (
                  SELECT 1
                  FROM recommendation_dismissals dismissal
                  WHERE dismissal.user_id = :userId
                    AND dismissal.candidate_product_id = p.id
                    AND dismissal.cooldown_until > CURRENT_TIMESTAMP
              )
            ORDER BY ph.price ASC, p.id ASC
            """, nativeQuery = true)
    List<CandidateProduct> findCompatibleCandidates(
            @Param("category") String category,
            @Param("budgetCeiling") BigDecimal budgetCeiling,
            @Param("currentProductId") Long currentProductId,
            @Param("userId") Long userId
    );
}