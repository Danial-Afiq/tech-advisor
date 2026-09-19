"""Query embedding.

Two implementations:

* `Model2VecEmbedder` is the real one, used with pgvector. Static embeddings
  (`minishlab/potion-retrieval-32M`, 512 dimensions): local, free, no API key,
  no torch, and fast enough to embed a query inline on the request path.
* `DeterministicEmbedder` is a hashed bag-of-words stand-in kept for the
  file-backed `LocalVectorStore` and for tests, where determinism matters more
  than meaning.

**Both sides of a comparison must use the same embedder.** A chunk embedded by
one model and a query embedded by another produce plausible nonsense rather
than an error - nothing in pgvector or here will catch it. That is why the
embedder name is recorded per chunk at ingestion time and checked on startup;
see `Settings.embedder` and `scripts/ingest.py`.

Measured on the demo corpus, against the query "how is the battery life after
a few months of ownership":

    chunk                                  potion-retrieval  deterministic
    battery noticeably worse                    +0.493          +0.224
    eight months in, battery holds up           +0.420          +0.302
    camera low light is a step up               +0.044          +0.185
    performance fine, gets warm                 -0.004          +0.167

The stand-in barely separates a camera passage from a battery one, which is
exactly the kind of weak evidence the grading prompt should never see.
"""

from __future__ import annotations

import hashlib
import math
import re
from functools import lru_cache
from typing import Any, Protocol

_TOKEN = re.compile(r"[a-z0-9]+")


class Embedder(Protocol):
    #: Identifies the vector space. Stamped into review_chunks.embedder at
    #: ingestion and compared at query time; see EmbedderMismatch.
    name: str
    dim: int

    def embed(self, text: str) -> list[float]: ...


class DeterministicEmbedder:
    """Hashed bag-of-words projected onto a unit sphere.

    Shares no vector space with any real embedding model. Two texts sharing
    vocabulary score closer than two that do not, which is enough to exercise
    ranking; it understands nothing.
    """

    name = "deterministic"

    def __init__(self, dim: int = 1536) -> None:
        self.dim = dim

    def embed(self, text: str) -> list[float]:
        vector = [0.0] * self.dim
        for token in _TOKEN.findall((text or "").lower()):
            digest = hashlib.blake2b(token.encode(), digest_size=8).digest()
            index = int.from_bytes(digest[:4], "big") % self.dim
            sign = 1.0 if digest[4] % 2 == 0 else -1.0
            vector[index] += sign
        norm = math.sqrt(sum(value * value for value in vector))
        if norm == 0.0:
            return vector
        return [value / norm for value in vector]


def cosine_distance(left: list[float], right: list[float]) -> float:
    """Mirrors pgvector's `<=>` operator, so ranking matches once the real
    store is wired in."""
    if not left or not right or len(left) != len(right):
        return 1.0
    dot = sum(a * b for a, b in zip(left, right))
    left_norm = math.sqrt(sum(a * a for a in left))
    right_norm = math.sqrt(sum(b * b for b in right))
    if left_norm == 0.0 or right_norm == 0.0:
        return 1.0
    return 1.0 - dot / (left_norm * right_norm)


#: Static-embedding model used with pgvector. 512 dimensions.
MODEL2VEC_MODEL = "minishlab/potion-retrieval-32M"
MODEL2VEC_DIM = 512


@lru_cache(maxsize=2)
def _load_static_model(name: str) -> Any:
    """Loading costs seconds and the object is stateless, so it is cached for
    the life of the process rather than rebuilt per request."""
    from model2vec import StaticModel

    return StaticModel.from_pretrained(name)


class Model2VecEmbedder:
    """Real semantic embeddings, computed locally.

    Static embeddings: a token-to-vector lookup with no transformer forward
    pass, so a query embeds in single-digit milliseconds on CPU and the
    request path does not need a GPU, a network call, or an API key.
    """

    def __init__(
        self,
        model_name: str = MODEL2VEC_MODEL,
        dim: int = MODEL2VEC_DIM,
        path: str = "",
    ) -> None:
        self.model_name = model_name
        # Identity stays the repo id even when the weights are loaded from a
        # local directory. It is written to review_chunks.embedder, so if the
        # container reported "/opt/model" and a developer's laptop reported
        # the repo id, every container query would trip EmbedderMismatch
        # against a corpus ingested from the host.
        self.name = model_name
        self.dim = dim
        self._model = _load_static_model(path or model_name)
        actual = int(self._model.dim)
        if actual != dim:
            # Refuse rather than silently write vectors the column cannot hold
            # or compare against chunks embedded at another width.
            raise ValueError(
                "%s produces %d dimensions but EMBEDDING_DIM is %d; the "
                "review_chunks.embedding column must match."
                % (model_name, actual, dim)
            )

    def embed(self, text: str) -> list[float]:
        vector = self._model.encode([text or ""])[0]
        return [float(value) for value in vector]

    def embed_many(self, texts: list[str]) -> list[list[float]]:
        """Batch path for ingestion. Much faster than one call per chunk."""
        if not texts:
            return []
        return [[float(v) for v in row] for row in self._model.encode(texts)]


def build_embedder(name: str, dim: int, path: str = "") -> Embedder:
    """`EMBEDDER` selects the implementation; `EMBEDDING_DIM` must agree.

    `path` (EMBEDDING_MODEL_PATH) loads the weights from a directory instead
    of the HuggingFace cache. The Docker image bakes them to /opt/model that
    way: a plain directory skips the hub's snapshot-completeness check, which
    otherwise refuses to load offline unless every file in the repo is
    present - including the 124 MB ONNX copy nothing here reads.
    """
    if name == "model2vec":
        return Model2VecEmbedder(dim=dim, path=path)
    if name == "deterministic":
        return DeterministicEmbedder(dim=dim)
    raise ValueError(
        "EMBEDDER must be 'model2vec' or 'deterministic' (got %r)." % name
    )
