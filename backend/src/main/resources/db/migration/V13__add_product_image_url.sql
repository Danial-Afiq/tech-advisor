-- Product thumbnail URL from the specifications source (MobileAPI.dev's image_url).
-- Nullable: older rows, manual catalogue entries and sources without images have none.
-- Only https URLs are accepted by ingestion; the frontend shows an icon when it is null.
ALTER TABLE products ADD COLUMN image_url TEXT;
