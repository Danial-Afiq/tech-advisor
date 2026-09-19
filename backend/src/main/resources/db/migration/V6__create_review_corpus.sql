-- The RAG corpus: the product a review is about, the source document, and the
-- embedded passages the AI layer retrieves.
--
-- Columns follow docs/Database Design.md sections 3 and 5. Only what the
-- retrieval path needs is created here; smartphone_specs, prices, benchmarks
-- and the recommendation tables remain outstanding.
--
-- Requires V4 (CREATE EXTENSION vector), which in turn requires a Postgres
-- image that ships pgvector - see docker-compose.yml.

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
    'Nullable on purpose. The maturity gate must handle an unknown release '
    'date rather than assume one - see the AI layer contract in README.md.';

CREATE TABLE review_documents (
    id            BIGSERIAL   PRIMARY KEY,
    product_id    BIGINT      NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    source_name   TEXT        NOT NULL,
    source_url    TEXT,
    title         TEXT,
    published_at  DATE,
    ingested_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON COLUMN review_documents.published_at IS
    'Age of the evidence, distinct from ingested_at which is our import '
    'history. Nullable: a passage whose document has no published_at is '
    'labelled rather than guessed at.';

CREATE INDEX review_documents_product_idx ON review_documents (product_id);

CREATE TABLE review_chunks (
    id                  BIGSERIAL   PRIMARY KEY,
    review_document_id  BIGINT       NOT NULL
                        REFERENCES review_documents (id) ON DELETE CASCADE,
    chunk_index         INTEGER      NOT NULL,
    chunk_text          TEXT         NOT NULL,
    embedding           vector(512)  NOT NULL,
    embedder            TEXT         NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT review_chunks_document_index_unique
        UNIQUE (review_document_id, chunk_index)
);

-- 512 dimensions is minishlab/potion-retrieval-32M, the embedder the AI layer
-- ships with. Changing the model means changing this column width AND
-- re-embedding every row: vectors from two different models share no space,
-- and a mismatch returns confident nonsense rather than an error. `embedder`
-- records which model produced each row so that mismatch is detectable.
COMMENT ON COLUMN review_chunks.embedding IS
    'pgvector embedding. Width is tied to the model named in embedder.';
COMMENT ON COLUMN review_chunks.embedder IS
    'Model that produced this embedding, e.g. minishlab/potion-retrieval-32M. '
    'Compared against the AI layer EMBEDDER setting at startup.';

CREATE INDEX review_chunks_document_idx ON review_chunks (review_document_id);

-- HNSW over cosine distance, matching the `<=>` operator in SEARCH_SQL.
-- Deliberately created after the table and before bulk load is small enough
-- not to matter here; at real corpus size, load first and index after.
CREATE INDEX review_chunks_embedding_idx
    ON review_chunks USING hnsw (embedding vector_cosine_ops);
