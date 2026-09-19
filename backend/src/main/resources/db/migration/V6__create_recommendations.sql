-- The recommendations table: the persisted output of one AI assessment for one
-- (user, candidate product) pair. Columns follow docs/Database Design.md §6 /
-- AGENTS.md §14.11.
--
-- Scope note: this migration only persists results. It does not assume the
-- deterministic verdict engine (Channel A / user_devices / device_preferences,
-- AGENTS.md §7.1) or market_events exist yet - those are separate tickets.
-- current_device_id and trigger_event_id are therefore nullable BIGINT with no
-- foreign key for now; whoever ships user_devices/market_events should add
-- FK constraints (and NOT NULL where appropriate) in a later migration rather
-- than editing this one, per AGENTS.md §31.

CREATE TABLE recommendations (
    id                     BIGSERIAL    PRIMARY KEY,
    user_id                BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    current_device_id      BIGINT,
    candidate_product_id   BIGINT       NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    trigger_event_id       BIGINT,
    verdict                TEXT         NOT NULL,
    confidence             TEXT,
    input_snapshot         JSONB        NOT NULL DEFAULT '{}',
    factor_analysis        JSONB        NOT NULL DEFAULT '{}',
    reasoning              TEXT,
    ai_model               TEXT,
    prompt_version         TEXT,
    status                 TEXT         NOT NULL DEFAULT 'ACTIVE',
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON COLUMN recommendations.current_device_id IS
    'No FK yet: user_devices does not exist on this branch. Add the '
    'constraint in the migration that creates user_devices, not here.';
COMMENT ON COLUMN recommendations.trigger_event_id IS
    'No FK yet: market_events does not exist on this branch. Add the '
    'constraint in the migration that creates market_events, not here.';
COMMENT ON COLUMN recommendations.verdict IS
    'Java-owned deterministic verdict, e.g. NO_MEANINGFUL_CHANGE, '
    'WORTH_WATCHING, WORTH_CONSIDERING, STRONG_UPGRADE_CANDIDATE. Not '
    'produced or altered by the AI layer - see AGENTS.md §7.1.';
COMMENT ON COLUMN recommendations.confidence IS
    'The A-F / "-" owner-evidence grade from the AI layer, stored as text. '
    'Not a probability - see AGENTS.md §7.3/§14.11. "-" means insufficient '
    'evidence, including every degraded assessment path.';
COMMENT ON COLUMN recommendations.input_snapshot IS
    'Generation-time context: owned device, preferences, candidate, '
    'computed deltas, and the AI layer''s retrieval parameters/retrieved '
    'chunk ids, so the recommendation stays auditable after live data '
    'changes - see AGENTS.md §14.11.';
COMMENT ON COLUMN recommendations.factor_analysis IS
    'JSONB with "deterministic" and "evidence" kept separate - never '
    'collapsed into one opaque score. Evidence entries carry '
    'supporting_chunk_ids (review_chunks.id), never the per-request P* '
    'refs - see AGENTS.md §8.6/§14.11.';
COMMENT ON COLUMN recommendations.status IS
    'Lifecycle state: active, superseded, dismissed, or expired - see '
    'AGENTS.md §26.3. Exact expiry/supersession policy is unresolved '
    '(AGENTS.md §27.6) and out of scope here; every row this feature '
    'writes uses ACTIVE.';

CREATE INDEX recommendations_user_idx ON recommendations (user_id);
CREATE INDEX recommendations_candidate_idx ON recommendations (candidate_product_id);
CREATE INDEX recommendations_user_candidate_idx ON recommendations (user_id, candidate_product_id, created_at DESC);

-- "Per-user/per-product" persistence means one current answer per pair, not an
-- unbounded pile of rows: re-assessment supersedes rather than duplicates.
-- The application flips the old row to SUPERSEDED before inserting the new
-- one, in the same transaction; this index makes that invariant enforced by
-- the database rather than only by application discipline.
CREATE UNIQUE INDEX recommendations_active_unique
    ON recommendations (user_id, candidate_product_id)
    WHERE status = 'ACTIVE';
