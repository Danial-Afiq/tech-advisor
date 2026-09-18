"""Passage retrieval.

Two implementations behind one interface:

* `LocalVectorStore` reads chunks from JSON files on disk. This is the dummy
  store standing in until pgvector is enabled. It starts empty.
* `PgVectorStore` is the real one, built around `SEARCH_SQL` below. It is not
  wired up yet because the `review_chunks` table and the vector extension do
  not exist; the SQL is here so the two stores cannot drift apart.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Any, Protocol

from app.retrieval.embedder import Embedder, cosine_distance

#: Section 5.2. The `product_id` filter is mandatory: without it, passages
#: about other phones that happen to be semantically similar get retrieved and
#: graded as if they described the candidate.
SEARCH_SQL = """
SELECT rc.id, rc.chunk_text, rd.published_at, rd.source_name
FROM review_chunks rc
JOIN review_documents rd ON rc.review_document_id = rd.id
WHERE rd.product_id = %(product_id)s
ORDER BY rc.embedding <=> %(query_embedding)s
LIMIT %(k)s
"""


@dataclass(frozen=True)
class Chunk:
    chunk_id: int
    product_id: int
    chunk_text: str
    source_name: str
    published_at: date | None = None
    embedding: list[float] | None = None


class VectorStore(Protocol):
    def search(
        self, product_id: int, query_embedding: list[float], k: int
    ) -> list[Chunk]: ...


class LocalVectorStore:
    """File-backed stand-in for pgvector.

    Reads every `*.json` file under `path`. Each file holds a list of chunk
    objects: `chunk_id`, `product_id`, `chunk_text`, `source_name`, optional
    `published_at` and optional `embedding`. Missing embeddings are computed
    on load, so fixtures can be plain text.

    A missing or empty directory is a legitimate state, not an error - it is
    what an un-ingested product looks like, and it makes the maturity gate the
    only thing standing between an empty corpus and a grade.
    """

    def __init__(self, path: str | Path, embedder: Embedder) -> None:
        self.path = Path(path)
        self.embedder = embedder
        self._chunks: list[Chunk] | None = None

    def _load(self) -> list[Chunk]:
        if self._chunks is not None:
            return self._chunks
        chunks: list[Chunk] = []
        if self.path.is_dir():
            for file in sorted(self.path.glob("*.json")):
                payload = json.loads(file.read_text(encoding="utf-8"))
                for raw in payload:
                    chunks.append(self._to_chunk(raw))
        self._chunks = chunks
        return chunks

    def _to_chunk(self, raw: dict[str, Any]) -> Chunk:
        published_at = raw.get("published_at")
        text = raw.get("chunk_text", "")
        return Chunk(
            chunk_id=int(raw["chunk_id"]),
            product_id=int(raw["product_id"]),
            chunk_text=text,
            source_name=raw.get("source_name", ""),
            published_at=date.fromisoformat(published_at) if published_at else None,
            embedding=raw.get("embedding") or self.embedder.embed(text),
        )

    def search(
        self, product_id: int, query_embedding: list[float], k: int
    ) -> list[Chunk]:
        candidates = [c for c in self._load() if c.product_id == product_id]
        candidates.sort(
            key=lambda c: (
                cosine_distance(c.embedding or [], query_embedding),
                c.chunk_id,
            )
        )
        return candidates[:k]


class PgVectorStore:
    """Real store. Unused until the vector extension and `review_chunks` exist.

    Kept in the tree so `SEARCH_SQL` has exactly one definition and the switch
    is a config change rather than a rewrite.
    """

    def __init__(self, connection: Any) -> None:
        self.connection = connection

    def search(
        self, product_id: int, query_embedding: list[float], k: int
    ) -> list[Chunk]:
        with self.connection.cursor() as cursor:
            cursor.execute(
                SEARCH_SQL,
                {
                    "product_id": product_id,
                    "query_embedding": query_embedding,
                    "k": k,
                },
            )
            return [
                Chunk(
                    chunk_id=row[0],
                    product_id=product_id,
                    chunk_text=row[1],
                    published_at=row[2].date() if row[2] else None,
                    source_name=row[3],
                )
                for row in cursor.fetchall()
            ]
