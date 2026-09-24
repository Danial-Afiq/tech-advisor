package com.springboot.backend.ingestion.searchapi;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SearchApiRepository {
    public static final String PROVIDER = "SEARCHAPI_GOOGLE_SHOPPING";
    private final JdbcTemplate db;
    public SearchApiRepository(JdbcTemplate db) { this.db = db; }
    public record Product(long id, String brand, String model) {
        public String name() { return brand + " " + model; }
    }
    public List<Product> products(int limit) {
        return db.query("SELECT id, brand, model_name FROM products WHERE status='VERIFIED' "
                + "AND category='SMARTPHONE' ORDER BY id LIMIT ?",
                (r, n) -> new Product(r.getLong(1), r.getString(2), r.getString(3)), limit);
    }
    public Optional<Product> eligibleProduct(long productId) {
        return db.query("SELECT id, brand, model_name FROM products WHERE id=? "
                + "AND status='VERIFIED' AND category='SMARTPHONE'",
                (r, n) -> new Product(r.getLong(1), r.getString(2), r.getString(3)), productId).stream().findFirst();
    }
    /** Exact catalogue names only. Two rows suffice to detect an ambiguous model-only name. */
    public List<Product> namedProducts(String normalizedName) {
        return db.query("""
                SELECT id, brand, model_name FROM products
                WHERE status='VERIFIED' AND category='SMARTPHONE'
                  AND (lower(regexp_replace(trim(brand || ' ' || model_name), '\\s+', ' ', 'g'))=?
                    OR lower(regexp_replace(trim(model_name), '\\s+', ' ', 'g'))=?)
                ORDER BY id LIMIT 2
                """, (r, n) -> new Product(r.getLong(1), r.getString(2), r.getString(3)),
                normalizedName, normalizedName);
    }
    public List<Product> namedCatalogueProducts(String normalizedName) {
        return db.query("""
                SELECT id, brand, model_name FROM products
                WHERE lower(regexp_replace(trim(brand || ' ' || model_name), '\\s+', ' ', 'g'))=?
                   OR lower(regexp_replace(trim(model_name), '\\s+', ' ', 'g'))=?
                ORDER BY id LIMIT 2
                """, (r, n) -> new Product(r.getLong(1), r.getString(2), r.getString(3)),
                normalizedName, normalizedName);
    }

    /** Promote only after SearchAPI returned one unambiguous matching product identity. */
    @Transactional
    public Product createVerified(ProductName requested, SearchApiSettings settings,
                                  ProductMatcher.Match match, Instant now) {
        var inserted = db.query("""
                INSERT INTO products(brand,model_name,category,status)
                VALUES (?,?,'SMARTPHONE','VERIFIED')
                ON CONFLICT (brand,model_name) DO NOTHING RETURNING id,brand,model_name
                """, (r, n) -> new Product(r.getLong(1), r.getString(2), r.getString(3)),
                requested.brand(), requested.model());
        Product product;
        if (!inserted.isEmpty()) product = inserted.getFirst();
        else product = db.query("""
                SELECT id,brand,model_name FROM products
                WHERE brand=? AND model_name=? AND category='SMARTPHONE' AND status='VERIFIED'
                """, (r, n) -> new Product(r.getLong(1), r.getString(2), r.getString(3)),
                requested.brand(), requested.model()).stream().findFirst()
                .orElseThrow(() -> new com.springboot.backend.ingestion.IngestionFailure(
                        com.springboot.backend.ingestion.IngestionFailure.Code.SEARCHAPI_NO_ELIGIBLE_PRODUCT));
        db.update("INSERT INTO phone(product_id) VALUES (?) ON CONFLICT DO NOTHING", product.id());
        cache(product, settings, match, now);
        return product;
    }
    public Optional<String> token(Product p, SearchApiSettings s) {
        return db.query("""
                SELECT product_token FROM external_product_mapping
                WHERE product_id=? AND provider=? AND gl=? AND hl=? AND location=?
                  AND canonical_name=? AND status='VALID' AND product_token IS NOT NULL
                """, (r, n) -> r.getString(1), p.id(), PROVIDER, s.gl(), s.hl(), s.location(), p.name())
                .stream().findFirst();
    }
    public void cache(Product p, SearchApiSettings s, ProductMatcher.Match m, Instant now) {
        db.update("""
                INSERT INTO external_product_mapping
                  (product_id,provider,gl,hl,location,external_product_id,product_token,matched_title,
                   canonical_name,matched_at,last_verified_at,status)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,'VALID')
                ON CONFLICT (product_id,provider,gl,hl,location) DO UPDATE SET
                  external_product_id=EXCLUDED.external_product_id, product_token=EXCLUDED.product_token,
                  matched_title=EXCLUDED.matched_title, canonical_name=EXCLUDED.canonical_name,
                  matched_at=EXCLUDED.matched_at, last_verified_at=EXCLUDED.last_verified_at, status='VALID'
                """, p.id(), PROVIDER, s.gl(), s.hl(), s.location(), m.externalId(), m.token(), m.title(),
                p.name(), Timestamp.from(now), Timestamp.from(now));
    }
    public void invalidate(Product p, SearchApiSettings s) {
        db.update("""
                UPDATE external_product_mapping SET status='INVALID', product_token=NULL
                WHERE product_id=? AND provider=? AND gl=? AND hl=? AND location=?
                """, p.id(), PROVIDER, s.gl(), s.hl(), s.location());
    }
    public void verified(Product p, SearchApiSettings s, Instant now) {
        db.update("""
                UPDATE external_product_mapping SET last_verified_at=?
                WHERE product_id=? AND provider=? AND gl=? AND hl=? AND location=? AND status='VALID'
                """, Timestamp.from(now), p.id(), PROVIDER, s.gl(), s.hl(), s.location());
    }
}
