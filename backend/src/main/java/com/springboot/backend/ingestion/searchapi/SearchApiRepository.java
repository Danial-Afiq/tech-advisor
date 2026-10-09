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
    /**
     * Excludes products already attempted for this provider/locale (VALID or
     * INVALID row in external_product_mapping) - otherwise the untargeted
     * picker re-selects the same oldest-by-id product forever once one
     * mapping row exists, and never advances through the rest of the
     * catalogue. See SearchApiSource.ingest()/markNoMatch().
     */
    public List<Product> products(SearchApiSettings s) {
        return db.query("""
                SELECT id, brand, model_name FROM products p
                WHERE status='VERIFIED' AND category='SMARTPHONE'
                  AND NOT EXISTS (SELECT 1 FROM external_product_mapping m
                                  WHERE m.product_id=p.id AND m.provider=? AND m.gl=? AND m.hl=? AND m.location=?)
                ORDER BY id LIMIT ?
                """, (r, n) -> new Product(r.getLong(1), r.getString(2), r.getString(3)),
                SearchApiSource.PROVIDER, s.gl(), s.hl(), s.location(), s.maxProductsPerRun());
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
    /**
     * Records "we looked, there is no valid Google listing for this
     * product" so the untargeted picker in products() doesn't retry it
     * every run forever. external_product_id/matched_title are NOT NULL
     * with nothing real to put there for a no-match outcome - uses the
     * same sentinel as a documented placeholder, exactly like an INVALID
     * row from invalidate() already means "don't trust the detail columns,
     * only the status/timestamps are meaningful."
     */
    private static final String NO_MATCH_SENTINEL = "NO_MATCH_FOUND";

    public void markNoMatch(Product p, SearchApiSettings s, Instant now) {
        db.update("""
                INSERT INTO external_product_mapping
                  (product_id,provider,gl,hl,location,external_product_id,product_token,matched_title,
                   canonical_name,matched_at,last_verified_at,status)
                VALUES (?,?,?,?,?,?,NULL,?,?,?,?,'INVALID')
                ON CONFLICT (product_id,provider,gl,hl,location) DO UPDATE SET
                  last_verified_at=EXCLUDED.last_verified_at, status='INVALID'
                """, p.id(), SearchApiSource.PROVIDER, s.gl(), s.hl(), s.location(),
                NO_MATCH_SENTINEL, NO_MATCH_SENTINEL, p.name(), Timestamp.from(now), Timestamp.from(now));
    }

    public void invalidate(Product p, SearchApiSettings s) {
        db.update("""
                UPDATE external_product_mapping SET status='INVALID', product_token=NULL
                WHERE product_id=? AND provider=? AND gl=? AND hl=? AND location=?
                """, p.id(), SearchApiSource.PROVIDER, s.gl(), s.hl(), s.location());
    }
    /**
     * Resolves a storage-GB figure parsed from a Google Shopping listing title to one exact
     * phone_variants row, when unambiguous. Storage alone can collide (a region or chipset
     * split within the same storage tier) - an ambiguous match returns empty rather than
     * guessing which row the listing's price actually belongs to; the caller records the
     * price at the model level instead of attaching it to the wrong SKU.
     */
    public Optional<Long> variantIdFor(long productId, int storageGb) {
        var ids = db.queryForList(
                "SELECT id FROM phone_variants WHERE product_id=? AND storage_gb=?",
                Long.class, productId, storageGb);
        return ids.size() == 1 ? Optional.of(ids.getFirst()) : Optional.empty();
    }

    public void verified(Product p, SearchApiSettings s, Instant now) {
        db.update("""
                UPDATE external_product_mapping SET last_verified_at=?
                WHERE product_id=? AND provider=? AND gl=? AND hl=? AND location=? AND status='VALID'
                """, Timestamp.from(now), p.id(), SearchApiSource.PROVIDER, s.gl(), s.hl(), s.location());
    }
}
