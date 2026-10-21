-- Bugfix: MobileApiSmartphoneSource called the exact same MobileAPI URL on every
-- run (no page parameter at all), so a scheduled run never discovered anything
-- beyond whatever the first page of the default/unset-year listing happened to
-- be - confirmed live, not a fixed "latest first" order either. A real, ongoing
-- ingestion source needs to remember where it left off between runs so it can
-- actually sweep forward through new catalogue pages instead of looping on one.
CREATE TABLE ingestion_cursors (
    source_id   TEXT        PRIMARY KEY,
    state       JSONB       NOT NULL DEFAULT '{}',
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ingestion_cursors IS
    'Per-source pagination/progress state that must survive between runs, '
    'keyed by IngestionSource.sourceId(). Not a log - one row per source, '
    'overwritten in place.';
COMMENT ON COLUMN ingestion_cursors.state IS
    'Source-defined shape. mobileapi-smartphone stores {"year": <int>, "page": <int>}.';
