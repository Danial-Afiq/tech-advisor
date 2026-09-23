"""Load review passages into pgvector.

Reads the same JSON shape the file-backed store uses (see
`ai/data/vector_store/`), embeds every passage with the configured embedder,
and writes products, review_documents and review_chunks.

    python -m scripts.ingest                      # the committed demo corpus
    python -m scripts.ingest path/to/chunks.json
    python -m scripts.ingest --truncate           # clear the corpus first

This stands in for the real ingestion pipeline, which is the Java side's job
(docs/ingestion.md). It exists so the retrieval path can be exercised against
a real database before that pipeline lands.

Two things it is careful about:

* **The embedder is recorded per chunk.** Vectors from two different models
  share no space, and comparing across them returns a confident, meaningless
  ranking rather than an error. `review_chunks.embedder` is what makes that
  detectable; `PgVectorStore` checks it on every search.
* **Re-running is safe.** Documents are keyed on (product_id, source_name,
  title) and chunks on (review_document_id, chunk_index), so a second run
  updates rather than duplicating - duplicated passages would quietly skew
  retrieval toward whatever was ingested twice.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any

import psycopg

from app.config import Settings
from app.retrieval.embedder import build_embedder
from app.retrieval.store import to_pgvector

# A passage in the JSON has no document of its own, so one synthetic document
# per (product, source) carries the source metadata the prompt header needs.
UPSERT_PRODUCT = """
INSERT INTO products (id, brand, model_name, category, release_date)
VALUES (%(id)s, %(brand)s, %(model_name)s, 'SMARTPHONE', %(release_date)s)
ON CONFLICT (id) DO UPDATE
    SET brand = EXCLUDED.brand,
        model_name = EXCLUDED.model_name,
        updated_at = CURRENT_TIMESTAMP
RETURNING id
"""

FIND_DOCUMENT = """
SELECT id FROM review_documents
WHERE product_id = %(product_id)s
  AND source_name = %(source_name)s
  AND published_at IS NOT DISTINCT FROM %(published_at)s
"""

INSERT_DOCUMENT = """
INSERT INTO review_documents (product_id, source_name, published_at, title)
VALUES (%(product_id)s, %(source_name)s, %(published_at)s, %(title)s)
RETURNING id
"""

UPSERT_CHUNK = """
INSERT INTO review_chunks
    (review_document_id, chunk_index, chunk_text, embedding, embedder)
VALUES
    (%(document_id)s, %(chunk_index)s, %(chunk_text)s, %(embedding)s::vector,
     %(embedder)s)
ON CONFLICT (review_document_id, chunk_index) DO UPDATE
    SET chunk_text = EXCLUDED.chunk_text,
        embedding  = EXCLUDED.embedding,
        embedder   = EXCLUDED.embedder
"""


def load(path: Path) -> list[dict[str, Any]]:
    if path.is_dir():
        rows: list[dict[str, Any]] = []
        for file in sorted(path.glob("*.json")):
            rows.extend(json.loads(file.read_text(encoding="utf-8")))
        return rows
    return json.loads(path.read_text(encoding="utf-8"))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "source",
        nargs="?",
        help="JSON file or directory of them. Defaults to the demo corpus.",
    )
    parser.add_argument(
        "--truncate",
        action="store_true",
        help="Delete the existing corpus first. Products are left alone.",
    )
    args = parser.parse_args(argv)

    settings = Settings()
    source = Path(args.source) if args.source else settings.resolved_vector_store_path
    rows = load(source)
    if not rows:
        print(f"no passages found in {source}")
        return 1

    embedder = build_embedder(settings.embedder, settings.embedding_dim)
    print(f"source   : {source}")
    print(f"passages : {len(rows)}")
    print(f"embedder : {embedder.name} ({embedder.dim} dims)")

    texts = [r.get("chunk_text", "") for r in rows]
    vectors = (
        embedder.embed_many(texts)
        if hasattr(embedder, "embed_many")
        else [embedder.embed(t) for t in texts]
    )

    # Group passages into one synthetic document per (product, source, date).
    grouped: dict[tuple[int, str, str | None], list[tuple[dict, list[float]]]] = (
        defaultdict(list)
    )
    for row, vector in zip(rows, vectors):
        key = (
            int(row["product_id"]),
            row.get("source_name") or "unknown",
            row.get("published_at"),
        )
        grouped[key].append((row, vector))

    written = 0
    with psycopg.connect(settings.dsn) as conn:
        with conn.cursor() as cur:
            if args.truncate:
                cur.execute("TRUNCATE review_chunks, review_documents CASCADE")
                print("truncated review_chunks and review_documents")

            for (product_id, source_name, published_at), items in grouped.items():
                cur.execute(
                    UPSERT_PRODUCT,
                    {
                        "id": product_id,
                        "brand": "Unknown",
                        "model_name": f"product-{product_id}",
                        "release_date": None,
                    },
                )
                cur.execute(
                    FIND_DOCUMENT,
                    {
                        "product_id": product_id,
                        "source_name": source_name,
                        "published_at": published_at,
                    },
                )
                found = cur.fetchone()
                if found:
                    document_id = found[0]
                else:
                    cur.execute(
                        INSERT_DOCUMENT,
                        {
                            "product_id": product_id,
                            "source_name": source_name,
                            "published_at": published_at,
                            "title": None,
                        },
                    )
                    document_id = cur.fetchone()[0]

                for index, (row, vector) in enumerate(items):
                    cur.execute(
                        UPSERT_CHUNK,
                        {
                            "document_id": document_id,
                            "chunk_index": index,
                            "chunk_text": row.get("chunk_text", ""),
                            "embedding": to_pgvector(vector),
                            "embedder": embedder.name,
                        },
                    )
                    written += 1

            # products.id came from the JSON, so the sequence still points at
            # 1 and the next real insert would collide.
            cur.execute(
                "SELECT setval(pg_get_serial_sequence('products','id'),"
                " GREATEST((SELECT COALESCE(MAX(id),1) FROM products), 1))"
            )
        conn.commit()

    print(f"wrote {written} chunks across {len(grouped)} documents")
    return 0


if __name__ == "__main__":
    sys.exit(main())
