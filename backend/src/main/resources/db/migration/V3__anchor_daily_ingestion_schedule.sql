-- User-requested cadence change: 17 September 2026 13:00 SGT = 05:00 UTC.
-- Preserve execution history, source cooldowns and ownership. Reset only the schedule.
UPDATE system_log
SET metadata = jsonb_set(
    jsonb_set(metadata, '{anchor}', '"2026-09-17T05:00:00Z"'::jsonb),
    '{nextDue}', '"2026-09-17T05:00:00Z"'::jsonb)
WHERE component = 'INGESTION_COORDINATOR';
