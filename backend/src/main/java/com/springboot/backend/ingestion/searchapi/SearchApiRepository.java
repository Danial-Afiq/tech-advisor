package com.springboot.backend.ingestion.searchapi;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SearchApiRepository {
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
    /**
     * Fuzzy-matched against ProductMatcher's token logic, not exact-string
     * equality - a catalogue name (from MobileAPI, the sole source of truth
     * for `products` rows) may carry extra suffix words (colour, storage,
     * "5G") the admin didn't type, same tolerance already proven against
     * real Google Shopping titles. Matches either "Brand Model" or a bare
     * "Model" alone. No brand pre-filter: a bare model-only query has no
     * brand to filter on, so this scans every eligible row - fine at this
     * catalogue's scale, revisit if it ever grows large. Two rows suffice
     * to detect an ambiguous name.
     */
    public List<Product> namedProducts(String rawName) {
        var candidates = db.query("""
                SELECT id, brand, model_name FROM products WHERE status='VERIFIED' AND category='SMARTPHONE'
                ORDER BY id
                """, (r, n) -> new Product(r.getLong(1), r.getString(2), r.getString(3)));
        return ProductMatcher.matchCatalogue(rawName, candidates, Product::name, Product::model).stream()
                .limit(2).toList();
    }
    /** Same match, without the VERIFIED/SMARTPHONE filter - distinguishes "doesn't exist" from "exists but ineligible". */
    public List<Product> namedCatalogueProducts(String rawName) {
        var candidates = db.query("SELECT id, brand, model_name FROM products ORDER BY id",
                (r, n) -> new Product(r.getLong(1), r.getString(2), r.getString(3)));
        return ProductMatcher.matchCatalogue(rawName, candidates, Product::name, Product::model).stream()
                .limit(2).toList();
    }
    public Optional<String> token(Product p, SearchApiSettings s) {
        return db.query("""
                SELECT product_token FROM external_product_mapping
                WHERE product_id=? AND provider=? AND gl=? AND hl=? AND location=?
                  AND canonical_name=? AND status='VALID' AND product_token IS NOT NULL
                """, (r, n) -> r.getString(1), p.id(), SearchApiSource.PROVIDER,
                s.gl(), s.hl(), s.location(), p.name())
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
                """, p.id(), SearchApiSource.PROVIDER, s.gl(), s.hl(), s.location(),
                m.externalId(), m.token(), m.title(),
                p.name(), Timestamp.from(now), Timestamp.from(now));
    }
    public void invalidate(Product p, SearchApiSettings s) {
        db.update("""
                UPDATE external_product_mapping SET status='INVALID', product_token=NULL
                WHERE product_id=? AND provider=? AND gl=? AND hl=? AND location=?
                """, p.id(), SearchApiSource.PROVIDER, s.gl(), s.hl(), s.location());
    }
    public void verified(Product p, SearchApiSettings s, Instant now) {
        db.update("""
                UPDATE external_product_mapping SET last_verified_at=?
                WHERE product_id=? AND provider=? AND gl=? AND hl=? AND location=? AND status='VALID'
                """, Timestamp.from(now), p.id(), SearchApiSource.PROVIDER, s.gl(), s.hl(), s.location());
    }
}
