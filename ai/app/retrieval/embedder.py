"""Query embedding.

`DeterministicEmbedder` is a stand-in, not a semantic model. It exists so the
retrieval path is exercisable before an embedding provider is chosen, and it
must be replaced at the same time as the real pgvector store: a chunk embedded
by one model and a query embedded by another produce plausible nonsense rather
than an error.
"""

from __future__ import annotations

import hashlib
import math
import re
from typing import Protocol

_TOKEN = re.compile(r"[a-z0-9]+")


class Embedder(Protocol):
    dim: int

    def embed(self, text: str) -> list[float]: ...


class DeterministicEmbedder:
    """Hashed bag-of-words projected onto a unit sphere.

    Shares no vector space with any real embedding model. Two texts sharing
    vocabulary score closer than two that do not, which is enough to exercise
    ranking; it understands nothing.
    """

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
