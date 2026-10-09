-- Ticket 1.8 follow-on: price_history is product_id-scoped only, so a price
-- can't be recorded against a specific SKU - but different storage/RAM
-- tiers of the same model really do cost different amounts. Additive and
-- nullable, same pattern as user_devices.phone_variant_id in V8: existing
-- rows and every current reader/writer of price_history keep working
-- unchanged against product_id; a price observation that IS known to be
-- for one exact variant (e.g. a SearchAPI Google Shopping listing matched
-- to a specific storage tier) can now say so.
ALTER TABLE price_history
    ADD COLUMN phone_variant_id BIGINT
        REFERENCES phone_variants (id) ON DELETE SET NULL;

COMMENT ON COLUMN price_history.phone_variant_id IS
    'The exact SKU this price was observed for, when known (ticket 1.8). '
    'Null means the observation is only known at the general-model level - '
    'e.g. MobileAPI misc.price, which does not break out price per tier.';

CREATE INDEX price_history_phone_variant_idx ON price_history (phone_variant_id);
