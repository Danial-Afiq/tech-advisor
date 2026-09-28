-- Additive: existing manual/demo documents retain NULL provider/fingerprint.
CREATE TABLE external_product_mapping (
    product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    provider TEXT NOT NULL,
    gl TEXT NOT NULL,
    hl TEXT NOT NULL,
    location TEXT NOT NULL,
    external_product_id TEXT NOT NULL,
    product_token TEXT,
    matched_title TEXT NOT NULL,
    canonical_name TEXT NOT NULL,
    matched_at TIMESTAMPTZ NOT NULL,
    last_verified_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('VALID', 'INVALID')),
    PRIMARY KEY (product_id, provider, gl, hl, location)
);

ALTER TABLE review_documents
    ADD COLUMN provider TEXT,
    ADD COLUMN external_fingerprint TEXT,
    ADD COLUMN metadata JSONB NOT NULL DEFAULT '{}';
CREATE UNIQUE INDEX review_documents_external_identity
    ON review_documents(product_id, provider, external_fingerprint);
COMMENT ON COLUMN review_documents.metadata IS
    'Allowlisted evidence provenance only: source domain, rating, raw date and retrieval timestamp. No reviewer profiles.';
