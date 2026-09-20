-- V7_create_and_update_all_schema_sprint_1.sql
--
-- Additive migration based on tech_advisor_updated_er(6).mmd.
-- All UUID identifiers in the ER diagram are represented as BIGSERIAL
-- primary keys or BIGINT foreign keys. Existing V1-V6 BIGSERIAL identifiers
-- are preserved; no primary-key type conversions or data drops are performed.
--
-- Existing naming from V6 is retained:
--   product -> products
--   user_device -> user_devices
--   device_preference -> device_preferences
--   price_history already exists and is preserved.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE products
    ADD COLUMN IF NOT EXISTS brand TEXT,
    ADD COLUMN IF NOT EXISTS model_name TEXT,
    ADD COLUMN IF NOT EXISTS category TEXT NOT NULL DEFAULT 'SMARTPHONE',
    ADD COLUMN IF NOT EXISTS release_date DATE,
    ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'VERIFIED',
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- Disjoint PRODUCT subtypes. product_id is the BIGINT FK to products.id.
CREATE TABLE IF NOT EXISTS phone (
    product_id BIGINT PRIMARY KEY REFERENCES products(id) ON DELETE CASCADE,
    chipset VARCHAR(255),
    ram_gb INTEGER,
    cpu_ghz NUMERIC,
    storage_gb INTEGER,
    battery_mah INTEGER,
    wired_charging_watts INTEGER,
    wireless_charging_watts INTEGER,
    display_size_inches NUMERIC,
    refresh_rate_hz INTEGER,
    weight_g INTEGER,
    camera_specs TEXT,
    pixel_density INTEGER,
    ip_rating VARCHAR(50),
    os VARCHAR(100),
    software_support_years NUMERIC
);

CREATE TABLE IF NOT EXISTS gpu (
    product_id BIGINT PRIMARY KEY REFERENCES products(id) ON DELETE CASCADE,
    core_count INTEGER,
    clock_speeds NUMERIC,
    vram NUMERIC,
    bus_width INTEGER,
    memory_speed NUMERIC,
    total_bandwidth NUMERIC,
    pixel_fillrate NUMERIC,
    texture_fillrate NUMERIC,
    tgp NUMERIC
);

-- V6 already creates price_history with BIGSERIAL id and BIGINT product_id.
ALTER TABLE price_history
    ADD COLUMN IF NOT EXISTS currency CHAR(3),
    ADD COLUMN IF NOT EXISTS source TEXT,
    ADD COLUMN IF NOT EXISTS observed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- V6 already creates user_devices and device_preferences; preserve their data.
ALTER TABLE user_devices
    ADD COLUMN IF NOT EXISTS user_id BIGINT,
    ADD COLUMN IF NOT EXISTS product_id BIGINT,
    ADD COLUMN IF NOT EXISTS custom_name TEXT,
    ADD COLUMN IF NOT EXISTS purchase_date DATE,
    ADD COLUMN IF NOT EXISTS condition TEXT,
    ADD COLUMN IF NOT EXISTS satisfaction_score INTEGER,
    ADD COLUMN IF NOT EXISTS use_cases JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS spec_overrides JSONB NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE device_preferences
    ADD COLUMN IF NOT EXISTS budget NUMERIC(12,2),
    ADD COLUMN IF NOT EXISTS currency CHAR(3) NOT NULL DEFAULT 'SGD',
    ADD COLUMN IF NOT EXISTS upgrade_urgency TEXT,
    ADD COLUMN IF NOT EXISTS brand_flexibility TEXT,
    ADD COLUMN IF NOT EXISTS priorities JSONB NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS pain_points JSONB NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS notes TEXT,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE TABLE IF NOT EXISTS benchmark_results (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    benchmark_name VARCHAR(255) NOT NULL,
    score NUMERIC NOT NULL,
    unit VARCHAR(100),
    higher_is_better BOOLEAN NOT NULL DEFAULT TRUE,
    source TEXT,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS market_events (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT REFERENCES products(id) ON DELETE SET NULL,
    event_type VARCHAR(100) NOT NULL,
    title VARCHAR(500) NOT NULL,
    description TEXT,
    old_value JSONB,
    new_value JSONB,
    source TEXT,
    detected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS recommendations (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    current_device_id BIGINT NOT NULL REFERENCES user_devices(id) ON DELETE CASCADE,
    candidate_product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    trigger_event_id BIGINT REFERENCES market_events(id) ON DELETE SET NULL,
    verdict NUMERIC NOT NULL,
    confidence NUMERIC,
    input_snapshot JSONB NOT NULL,
    factor_analysis JSONB NOT NULL,
    reasoning TEXT NOT NULL,
    ai_model VARCHAR(255) NOT NULL,
    prompt_version VARCHAR(100) NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS review_documents (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    source_name VARCHAR(255) NOT NULL,
    source_url TEXT NOT NULL,
    title VARCHAR(500),
    published_at TIMESTAMPTZ,
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS review_chunks (
    id BIGSERIAL PRIMARY KEY,
    review_document_id BIGINT NOT NULL REFERENCES review_documents(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    chunk_text TEXT NOT NULL,
    embedding VECTOR,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT review_chunks_document_index_unique UNIQUE (review_document_id, chunk_index)
);

-- V2 already creates system_log with BIGSERIAL id; retain existing operational indexes.
ALTER TABLE system_log
    ADD COLUMN IF NOT EXISTS metadata JSONB NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_phone_product_id ON phone(product_id);
CREATE INDEX IF NOT EXISTS idx_gpu_product_id ON gpu(product_id);
CREATE INDEX IF NOT EXISTS idx_benchmark_results_product_observed
    ON benchmark_results(product_id, observed_at DESC);
CREATE INDEX IF NOT EXISTS idx_market_events_product_detected
    ON market_events(product_id, detected_at DESC);
CREATE INDEX IF NOT EXISTS idx_recommendations_user_created
    ON recommendations(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_recommendations_current_device
    ON recommendations(current_device_id);
CREATE INDEX IF NOT EXISTS idx_recommendations_candidate_product
    ON recommendations(candidate_product_id);
CREATE INDEX IF NOT EXISTS idx_review_documents_product
    ON review_documents(product_id);
CREATE INDEX IF NOT EXISTS idx_review_chunks_document
    ON review_chunks(review_document_id);
