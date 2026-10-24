-- Ticket 1.3: GPU spec ingestion (TechSpot) and the gpus.v1 seed.
--
-- V6 created the gpu subtype table as schema readiness only. The backend
-- calculates spec deltas from typed columns, so the fields the ticket captures
-- get their own columns rather than a JSONB blob. Existing columns are reused
-- where the meaning matches: vram holds GB and bus_width holds bits.
-- clock_speeds and tgp stay in place but are not written by ingestion; base and
-- boost clocks are distinct values and board power is published as TDP.
--
-- Release date lives on products.release_date and launch MSRP is one
-- price_history row, so neither is duplicated here.
ALTER TABLE gpu
    ADD COLUMN base_clock_mhz  INTEGER,
    ADD COLUMN boost_clock_mhz INTEGER,
    ADD COLUMN memory_type     TEXT,
    ADD COLUMN architecture    TEXT,
    ADD COLUMN tdp_watts       INTEGER,
    ADD COLUMN source          TEXT,
    ADD COLUMN source_url      TEXT,
    ADD COLUMN fetched_at      TIMESTAMPTZ;

COMMENT ON COLUMN gpu.vram IS 'Video memory in GB.';
COMMENT ON COLUMN gpu.bus_width IS 'Memory bus width in bits.';
COMMENT ON COLUMN gpu.clock_speeds IS
    'Unused since V13; superseded by base_clock_mhz/boost_clock_mhz.';
COMMENT ON COLUMN gpu.tgp IS 'Unused since V13; superseded by tdp_watts.';
COMMENT ON COLUMN gpu.source IS
    'Provenance of the current values, e.g. seed:gpus.v1 or techspot.';
COMMENT ON COLUMN gpu.fetched_at IS
    'When the current values were last read from source; drives refresh order.';
