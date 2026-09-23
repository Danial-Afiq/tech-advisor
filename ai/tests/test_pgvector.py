"""pgvector integration.

These are the only tests that need a database. They **skip** when one is not
reachable, so `pytest` stays green on a laptop with nothing running and in CI
until a service container is configured - a skipped test is honest, a test
that silently passes without touching Postgres is not.

Run them for real with:

    docker compose up -d postgres
    cd backend && ./mvnw flyway:migrate -Dflyway.url=... (see README)
    # Set TEST_DATABASE_URL to the migrated database (name must end _test).
    # Optionally set TEST_EMBEDDING_MODEL_PATH for baked model weights.
    cd ai && python -m pytest tests/test_pgvector.py

Everything here writes to its own product ids (see PRODUCT_*) and cleans up
after itself, so it will not disturb the demo corpus.
"""

from __future__ import annotations

from typing import Any, Iterator
import os

import pytest

from app.config import Settings
from app.retrieval.embedder import build_embedder
from app.retrieval.store import EmbedderMismatch, PgVectorStore, to_pgvector

# Well outside anything the demo corpus or a human would pick.
PRODUCT_MAIN = 990001
PRODUCT_OTHER = 990002


def database_settings() -> Settings:
    # An explicit test DSN survives the general Settings-env isolation fixture.
    # Never write fixtures into the developer's normal database.
    dsn = os.environ.get("TEST_DATABASE_URL", "")
    if not dsn:
        pytest.skip("set TEST_DATABASE_URL to a dedicated database ending _test")
    return Settings(_env_file=None, database_url=dsn,
                    embedding_model_path=os.environ.get("TEST_EMBEDDING_MODEL_PATH", ""))


def _pool_or_skip() -> Any:
    settings = database_settings()
    try:
        import psycopg
        from psycopg_pool import ConnectionPool
    except ImportError:  # pragma: no cover - driver is a hard dependency
        pytest.skip("psycopg is not installed")

    try:
        with psycopg.connect(settings.dsn, connect_timeout=3) as conn:
            with conn.cursor() as cur:
                cur.execute("SELECT current_database()")
                if not cur.fetchone()[0].endswith("_test"):
                    pytest.fail("pgvector integration requires a database ending _test")
                cur.execute("SELECT to_regclass('public.review_chunks')")
                if cur.fetchone()[0] is None:
                    pytest.skip("review_chunks does not exist; run migrations V4+V6")
    except Exception as exc:  # noqa: BLE001 - any connection problem means skip
        pytest.skip("test database unavailable or migrations not applied")

    return ConnectionPool(settings.dsn, min_size=1, max_size=2, open=True, timeout=5.0)


@pytest.fixture(scope="module")
def pool() -> Iterator[Any]:
    p = _pool_or_skip()
    yield p
    p.close()


@pytest.fixture(scope="module")
def embedder() -> Any:
    settings = database_settings()
    return build_embedder(settings.embedder, settings.embedding_dim, settings.embedding_model_path)


@pytest.fixture(scope="module")
def corpus(pool: Any, embedder: Any) -> Iterator[None]:
    """A tiny corpus of our own, including a same-topic passage under a
    different product - the case the product filter exists to stop."""
    rows = [
        (PRODUCT_MAIN, "Reddit", "Battery drains far faster since the update."),
        (PRODUCT_MAIN, "GSMArena", "Low light camera output is much improved."),
        (PRODUCT_OTHER, "Reddit", "Battery on this other phone is atrocious."),
    ]
    with pool.connection() as conn:
        with conn.cursor() as cur:
            for product_id in (PRODUCT_MAIN, PRODUCT_OTHER):
                cur.execute(
                    """INSERT INTO products (id, brand, model_name)
                       VALUES (%s, 'TestBrand', %s)
                       ON CONFLICT (id) DO NOTHING""",
                    (product_id, f"test-{product_id}"),
                )
            for index, (product_id, source, text) in enumerate(rows):
                cur.execute(
                    """INSERT INTO review_documents (product_id, source_name)
                       VALUES (%s, %s) RETURNING id""",
                    (product_id, source),
                )
                document_id = cur.fetchone()[0]
                cur.execute(
                    """INSERT INTO review_chunks
                       (review_document_id, chunk_index, chunk_text, embedding, embedder)
                       VALUES (%s, %s, %s, %s::vector, %s)""",
                    (
                        document_id,
                        index,
                        text,
                        to_pgvector(embedder.embed(text)),
                        embedder.name,
                    ),
                )
        conn.commit()

    yield

    with pool.connection() as conn:
        with conn.cursor() as cur:
            # review_documents and review_chunks cascade from products.
            cur.execute(
                "DELETE FROM products WHERE id IN (%s, %s)",
                (PRODUCT_MAIN, PRODUCT_OTHER),
            )
        conn.commit()


@pytest.fixture
def store(pool: Any, embedder: Any, corpus: None) -> PgVectorStore:
    return PgVectorStore(pool, embedder_name=embedder.name)


def test_search_returns_this_products_passages(
    store: PgVectorStore, embedder: Any
) -> None:
    chunks = store.search(PRODUCT_MAIN, embedder.embed("battery"), k=10)
    assert len(chunks) == 2
    assert all(c.product_id == PRODUCT_MAIN for c in chunks)
    assert all(c.chunk_text for c in chunks)


def test_another_products_passage_never_leaks(
    store: PgVectorStore, embedder: Any
) -> None:
    """The decoy is *more* on-topic for this query than one of the real rows,
    so a missing product filter would rank it first rather than merely
    include it."""
    texts = [c.chunk_text for c in store.search(PRODUCT_MAIN, embedder.embed("battery"), k=10)]
    assert not any("other phone" in t for t in texts)


def test_ranking_is_semantic_not_lexical(
    store: PgVectorStore, embedder: Any
) -> None:
    """'photos in the dark' shares no words with the camera passage, so a
    keyword search would miss it entirely."""
    chunks = store.search(PRODUCT_MAIN, embedder.embed("taking photos in the dark"), k=10)
    assert "camera" in chunks[0].chunk_text.lower()


def test_k_limits_the_result_set(store: PgVectorStore, embedder: Any) -> None:
    assert len(store.search(PRODUCT_MAIN, embedder.embed("battery"), k=1)) == 1


def test_unknown_product_returns_nothing(
    store: PgVectorStore, embedder: Any
) -> None:
    """An un-ingested product is a legitimate state: the maturity gate, not an
    exception, is what keeps it from being graded."""
    assert store.search(987654, embedder.embed("battery"), k=10) == []


def test_embedder_is_recorded_per_chunk(
    store: PgVectorStore, embedder: Any
) -> None:
    assert store.embedders_for(PRODUCT_MAIN) == [embedder.name]


def test_mismatched_embedder_raises_rather_than_ranking_nonsense(
    pool: Any, embedder: Any, corpus: None
) -> None:
    """Comparing vectors from two models returns a confident, meaningless
    ordering. pgvector will not complain, so this check is the only thing
    standing between a model swap and silently garbage evidence."""
    store = PgVectorStore(pool, embedder_name="a-different-model")
    with pytest.raises(EmbedderMismatch, match="Re-run the ingestion"):
        store.search(PRODUCT_MAIN, embedder.embed("battery"), k=10)


def test_vector_column_width_matches_the_configured_embedder(pool: Any) -> None:
    """A width mismatch is a write-time error that would only surface during
    ingestion, long after the config was changed."""
    settings = database_settings()
    with pool.connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """SELECT atttypmod FROM pg_attribute
                   WHERE attrelid = 'review_chunks'::regclass
                     AND attname = 'embedding'"""
            )
            assert cur.fetchone()[0] == settings.embedding_dim
