-- Ticket 1.8 — schema redesign for multi-attribute phone entities.
--
-- `phone` is 1:1 with `products` today (product_id is its own primary key),
-- which forces every SKU of a model into one row: a 128GB and a 256GB
-- configuration of the same "Samsung Galaxy S24" cannot both exist, because
-- `products` enforces UNIQUE (brand, model_name). SmartphoneCatalogSink
-- currently resolves this by letting whichever variant is ingested last
-- silently overwrite the previous one's specs via ON CONFLICT - a real,
-- observed data-loss bug, not a hypothetical one.
--
-- This migration is additive and deliberately does NOT touch `phone`,
-- SmartphoneCatalogSink, SmartphoneCatalogueService, or any other code that
-- still reads/writes the old 1:1 table: rewiring ingestion, the admin CRUD
-- (ticket 1.7) and the device-selection flow onto phone_variants is explicit
-- follow-up work, not part of this schema step. `phone` and `phone_variants`
-- coexist until that follow-up lands.
--
-- `products` remains the general-model identity (brand/model_name). Specific
-- SKUs become rows here, one product having many variants.
CREATE TABLE phone_variants (
    id                      BIGSERIAL   PRIMARY KEY,
    product_id              BIGINT      NOT NULL
                             REFERENCES products (id) ON DELETE CASCADE,
    chipset                 TEXT,
    model_number            TEXT,
    ram_gb                  INTEGER,
    cpu_ghz                 NUMERIC,
    storage_gb              INTEGER,
    region                  TEXT,
    battery_mah             INTEGER,
    wired_charging_watts    INTEGER,
    wireless_charging_watts INTEGER,
    display_size_inches     NUMERIC,
    refresh_rate_hz         INTEGER,
    weight_g                INTEGER,
    camera_specs            TEXT,
    pixel_density           INTEGER,
    ip_rating               TEXT,
    os                      TEXT,
    software_support_years  NUMERIC,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- All five AC-named distinguishing attributes (chipset/SoC, RAM, storage,
    -- model number, region), not just three of them: two variants sharing
    -- storage/RAM/region but differing only in chipset or model_number must
    -- stay distinguishable (ticket 1.8 AC4) - storage/ram/region alone let a
    -- real chipset-only difference collide into one row.
    -- NULLS NOT DISTINCT (PG15+): two variants of the same product with every
    -- dimension unknown are still treated as the same row, not silently
    -- duplicated - no source gives a stable per-SKU identity to key on
    -- instead (see AGENTS.md on MobileAPI's storage/colors being free-text
    -- lists, not separate records).
    CONSTRAINT phone_variants_identity_unique UNIQUE NULLS NOT DISTINCT
        (product_id, storage_gb, ram_gb, region, chipset, model_number)
);

COMMENT ON TABLE phone_variants IS
    'One row per distinct SKU of a phone model (ticket 1.8). products stays '
    'the general-model identity; a model with no known variant breakdown yet '
    'simply has one row here with storage_gb/ram_gb/region left null.';
COMMENT ON COLUMN phone_variants.region IS
    'Regional/carrier distinction when known; null when the source does not '
    'distinguish region.';
COMMENT ON COLUMN phone_variants.model_number IS
    'The manufacturer''s own per-SKU model number when known (e.g. a specific '
    'entry from MobileAPI''s comma-separated misc.model_numbers list), not a '
    'Tech Advisor identifier. Null when the source does not break this out.';

CREATE INDEX phone_variants_product_idx ON phone_variants (product_id);

-- Backfill: every existing phone row becomes that product's one known
-- variant today. Real per-SKU breakdown arrives once ingestion is rewired
-- to parse MobileAPI's storage list / SearchAPI's per-listing price.
INSERT INTO phone_variants
    (product_id, chipset, ram_gb, cpu_ghz, storage_gb, battery_mah,
     wired_charging_watts, wireless_charging_watts, display_size_inches,
     refresh_rate_hz, weight_g, camera_specs, pixel_density, ip_rating,
     os, software_support_years)
SELECT product_id, chipset, ram_gb, cpu_ghz, storage_gb, battery_mah,
       wired_charging_watts, wireless_charging_watts, display_size_inches,
       refresh_rate_hz, weight_g, camera_specs, pixel_density, ip_rating,
       os, software_support_years
FROM phone;

-- `user_devices` should identify the exact variant a user owns, not just the
-- general model, so autofill can retrieve one unambiguous spec set (ticket
-- 1.8 AC). Additive and nullable: existing rows keep working unchanged via
-- product_id until DeviceService is updated to populate this too - that
-- rewiring, like the ingestion/CRUD one above, is explicit follow-up work.
ALTER TABLE user_devices
    ADD COLUMN phone_variant_id BIGINT
        REFERENCES phone_variants (id) ON DELETE SET NULL;

COMMENT ON COLUMN user_devices.phone_variant_id IS
    'The exact SKU the user owns, for unambiguous spec autofill (ticket 1.8). '
    'Nullable until device-selection is updated to populate it; product_id '
    'remains the general-model link in the meantime.';

CREATE INDEX user_devices_phone_variant_idx ON user_devices (phone_variant_id);
