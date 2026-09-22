-- Canonical Sprint 1 domain schema.
--
-- V1-V5 already own users, system_log, scheduler state, pgvector enablement,
-- and users.password_hash. This migration extends those foundations once and
-- creates the shared domain tables needed by candidate filtering, review RAG,
-- and recommendation persistence.

ALTER TABLE users
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE TABLE products (
    id            BIGSERIAL   PRIMARY KEY,
    brand         TEXT        NOT NULL,
    model_name    TEXT        NOT NULL,
    category      TEXT        NOT NULL DEFAULT 'SMARTPHONE',
    release_date  DATE,
    status        TEXT        NOT NULL DEFAULT 'VERIFIED',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT products_brand_model_unique UNIQUE (brand, model_name)
);

COMMENT ON COLUMN products.release_date IS
    'Nullable because catalogue ingestion may not know the release date; age '
    'and evidence-maturity logic must handle an unknown date explicitly.';
COMMENT ON COLUMN products.status IS
    'Catalogue verification/lifecycle state. Candidate filtering currently '
    'admits only VERIFIED products.';

-- Disjoint category-specific product details. Application logic is still
-- responsible for keeping products.category aligned with the populated
-- subtype; cross-table subtype enforcement can be added when subtype writes
-- are implemented.
CREATE TABLE phone (
    product_id                 BIGINT PRIMARY KEY
                               REFERENCES products (id) ON DELETE CASCADE,
    chipset                    TEXT,
    ram_gb                     INTEGER,
    cpu_ghz                    NUMERIC,
    storage_gb                 INTEGER,
    battery_mah                INTEGER,
    wired_charging_watts       INTEGER,
    wireless_charging_watts    INTEGER,
    display_size_inches        NUMERIC,
    refresh_rate_hz            INTEGER,
    weight_g                   INTEGER,
    camera_specs               TEXT,
    pixel_density              INTEGER,
    ip_rating                  TEXT,
    os                         TEXT,
    software_support_years     NUMERIC
);

CREATE TABLE gpu (
    product_id       BIGINT PRIMARY KEY
                     REFERENCES products (id) ON DELETE CASCADE,
    core_count       INTEGER,
    clock_speeds     NUMERIC,
    vram             NUMERIC,
    bus_width        INTEGER,
    memory_speed     NUMERIC,
    total_bandwidth  NUMERIC,
    pixel_fillrate   NUMERIC,
    texture_fillrate NUMERIC,
    tgp              NUMERIC
);

CREATE TABLE price_history (
    id          BIGSERIAL     PRIMARY KEY,
    product_id  BIGINT        NOT NULL
                REFERENCES products (id) ON DELETE CASCADE,
    price       NUMERIC(12,2) NOT NULL CHECK (price >= 0),
    currency    CHAR(3)       NOT NULL,
    source      TEXT,
    observed_at TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON COLUMN price_history.observed_at IS
    'When the source observed this price, not when Tech Advisor ingested it.';

CREATE TABLE benchmark_results (
    id               BIGSERIAL   PRIMARY KEY,
    product_id       BIGINT      NOT NULL
                     REFERENCES products (id) ON DELETE CASCADE,
    benchmark_name   TEXT        NOT NULL,
    score            NUMERIC     NOT NULL,
    unit             TEXT,
    higher_is_better BOOLEAN     NOT NULL DEFAULT TRUE,
    source           TEXT,
    observed_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_devices (
    id                 BIGSERIAL   PRIMARY KEY,
    user_id            BIGINT      NOT NULL
                       REFERENCES users (id) ON DELETE CASCADE,
    product_id         BIGINT
                       REFERENCES products (id) ON DELETE SET NULL,
    custom_name        TEXT,
    purchase_date      DATE,
    condition          TEXT,
    satisfaction_score INTEGER     CHECK (satisfaction_score BETWEEN 0 AND 100),
    use_cases          JSONB       NOT NULL DEFAULT '[]',
    is_current         BOOLEAN     NOT NULL DEFAULT TRUE,
    spec_overrides     JSONB       NOT NULL DEFAULT '{}',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON COLUMN user_devices.product_id IS
    'Nullable catalogue link for a device that is not yet represented in products.';
COMMENT ON COLUMN user_devices.spec_overrides IS
    'User-specific configuration differences from the shared catalogue product.';

CREATE TABLE device_preferences (
    user_device_id    BIGINT        PRIMARY KEY
                      REFERENCES user_devices (id) ON DELETE CASCADE,
    budget            NUMERIC(12,2) NOT NULL CHECK (budget >= 0),
    currency          CHAR(3)       NOT NULL DEFAULT 'SGD',
    upgrade_urgency   TEXT,
    brand_flexibility TEXT,
    priorities        JSONB         NOT NULL DEFAULT '{}',
    pain_points       JSONB         NOT NULL DEFAULT '{}',
    notes             TEXT,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON COLUMN device_preferences.budget IS
    'Required because deterministic candidate shortlisting needs a budget ceiling.';
COMMENT ON COLUMN device_preferences.currency IS
    'Stored with the budget. Current filtering does not perform currency conversion.';

CREATE TABLE market_events (
    id          BIGSERIAL   PRIMARY KEY,
    product_id  BIGINT
                REFERENCES products (id) ON DELETE SET NULL,
    event_type  TEXT        NOT NULL,
    title       TEXT        NOT NULL,
    description TEXT,
    old_value   JSONB,
    new_value   JSONB,
    source      TEXT,
    detected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE review_documents (
    id           BIGSERIAL   PRIMARY KEY,
    product_id   BIGINT      NOT NULL
                 REFERENCES products (id) ON DELETE CASCADE,
    source_name  TEXT        NOT NULL,
    source_url   TEXT,
    title        TEXT,
    published_at DATE,
    ingested_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON COLUMN review_documents.source_url IS
    'Nullable because demo/manual evidence may not have a real source URL.';
COMMENT ON COLUMN review_documents.published_at IS
    'External publication date, distinct from the Tech Advisor ingestion timestamp.';

CREATE TABLE review_chunks (
    id                 BIGSERIAL   PRIMARY KEY,
    review_document_id BIGINT      NOT NULL
                       REFERENCES review_documents (id) ON DELETE CASCADE,
    chunk_index        INTEGER     NOT NULL,
    chunk_text         TEXT        NOT NULL,
    embedding          vector(512) NOT NULL,
    embedder           TEXT        NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT review_chunks_document_index_unique
        UNIQUE (review_document_id, chunk_index)
);

COMMENT ON COLUMN review_chunks.embedding IS
    '512-dimensional pgvector value used by the configured Sprint 1 embedder.';
COMMENT ON COLUMN review_chunks.embedder IS
    'Embedding model identifier; query-time retrieval rejects model mismatches.';

CREATE TABLE recommendations (
    id                   BIGSERIAL   PRIMARY KEY,
    user_id              BIGINT      NOT NULL
                         REFERENCES users (id) ON DELETE CASCADE,
    current_device_id    BIGINT
                         REFERENCES user_devices (id) ON DELETE SET NULL,
    candidate_product_id BIGINT      NOT NULL
                         REFERENCES products (id) ON DELETE CASCADE,
    trigger_event_id     BIGINT
                         REFERENCES market_events (id) ON DELETE SET NULL,
    verdict              TEXT        NOT NULL,
    confidence           TEXT,
    input_snapshot       JSONB       NOT NULL DEFAULT '{}',
    factor_analysis      JSONB       NOT NULL DEFAULT '{}',
    reasoning            TEXT,
    ai_model             TEXT,
    prompt_version       TEXT,
    status               TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON COLUMN recommendations.verdict IS
    'Java-owned deterministic verdict stored as text.';
COMMENT ON COLUMN recommendations.confidence IS
    'A-F or "-" owner-evidence grade stored as text, not a probability.';
COMMENT ON COLUMN recommendations.factor_analysis IS
    'Keeps deterministic factor impacts separate from model evidence findings.';
COMMENT ON COLUMN recommendations.status IS
    'Lifecycle state; new rows default to ACTIVE.';

CREATE INDEX idx_product_category
    ON products (category);
CREATE INDEX idx_price_history_lookup
    ON price_history (product_id, observed_at DESC);
CREATE INDEX idx_benchmark_results_product_observed
    ON benchmark_results (product_id, observed_at DESC);
CREATE INDEX idx_user_devices_user
    ON user_devices (user_id);
CREATE INDEX idx_market_events_product_detected
    ON market_events (product_id, detected_at DESC);
CREATE INDEX review_documents_product_idx
    ON review_documents (product_id);
CREATE INDEX review_chunks_document_idx
    ON review_chunks (review_document_id);
CREATE INDEX review_chunks_embedding_idx
    ON review_chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX recommendations_user_idx
    ON recommendations (user_id);
CREATE INDEX recommendations_current_device_idx
    ON recommendations (current_device_id);
CREATE INDEX recommendations_candidate_idx
    ON recommendations (candidate_product_id);
CREATE INDEX recommendations_user_candidate_idx
    ON recommendations (user_id, candidate_product_id, created_at DESC);

CREATE UNIQUE INDEX recommendations_active_unique
    ON recommendations (user_id, candidate_product_id)
    WHERE status = 'ACTIVE';
