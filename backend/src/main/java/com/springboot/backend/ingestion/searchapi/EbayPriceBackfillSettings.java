package com.springboot.backend.ingestion.searchapi;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * One-time database upgrade (Oct 2026): every VERIFIED GPU and every VERIFIED smartphone from a
 * curated "popular" brand allowlist gets a first eBay price, instead of waiting for
 * {@link EbayPriceSource}'s round-robin to reach each of them for the first time over however
 * many days that naturally takes. Brands are config, not a hardcoded list, for the same reason
 * {@link SearchApiSettings#categories} and {@link EbayPriceSettings#categories} are: the "which
 * products" decision belongs in config, reviewable and changeable without a code change.
 *
 * The default brand list (Apple, Samsung, Google, Xiaomi, OnePlus, Redmi, POCO) is the "Tier 2"
 * cut from the real catalogue recce this chore was scoped against: every brand real users in
 * this app's own {@code user_devices} table actually own (Apple, Samsung, Google), plus the
 * next most recognizable mainstream-Android brands. GPUs are never brand-filtered - the whole
 * GPU catalogue is small enough, and undifferentiated enough by "popularity", to just backfill
 * all of it.
 */
@ConfigurationProperties("searchapi.ebay-backfill")
public record EbayPriceBackfillSettings(
        @DefaultValue("500") int maxProductsPerRun,
        @DefaultValue("Apple,Samsung,Google,Xiaomi,OnePlus,Redmi,POCO") List<String> smartphoneBrands) {
    @ConstructorBinding
    public EbayPriceBackfillSettings {
        // No per-product request-math ceiling like SearchApiSettings' 1-3: this is a deliberate
        // one-off operation sized to the real target count (see class Javadoc), not a recurring
        // scheduled budget, so the sane range is wide - large enough that one trigger can
        // realistically finish the whole backfill, small enough to catch a config typo.
        if (maxProductsPerRun < 1 || maxProductsPerRun > 5000)
            throw new IllegalArgumentException("SEARCHAPI_EBAY_BACKFILL_MAX_PRODUCTS_PER_RUN must be 1-5000");
        smartphoneBrands = smartphoneBrands == null ? List.of()
                : smartphoneBrands.stream().map(String::trim).filter(b -> !b.isBlank()).distinct().toList();
        if (smartphoneBrands.isEmpty())
            throw new IllegalArgumentException("SEARCHAPI_EBAY_BACKFILL_SMARTPHONE_BRANDS must not be empty");
    }
}
