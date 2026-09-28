"""FastAPI entry point.

POST /assess is called by Spring Boot once per
(user_device, candidate_product) pair that has cleared the maturity gate.
POST /internal/embed supplies bounded ingestion embeddings without an LLM call.

The service is stateless. With VECTOR_STORE=pgvector it holds read
credentials for review_chunks; it still writes nothing. It never writes to
`recommendations` or `system_log`; where section 10 calls for a log row, the
row is returned in the response for Spring to persist, so the failure and the
recommendation it belongs to are written in one place.
"""

from __future__ import annotations

import logging
import math
from functools import lru_cache
from typing import Any, Annotated

from fastapi import Depends, FastAPI, Header, HTTPException, status
from pydantic import BaseModel, ConfigDict, Field, StringConstraints

from app.assess import Assessor
from app.config import Settings, get_settings
from app.llm import build_llm
from app.retrieval.embedder import build_embedder
from app.retrieval.store import LocalVectorStore, PgVectorStore
from app.schemas import AssessRequest, AssessResponse

logging.basicConfig(level=logging.INFO)

app = FastAPI(
    title="Tech Advisor AI layer",
    description="Evidence grading and summary generation over owner reviews.",
    version="0.1.0",
)

_assessor: Assessor | None = None


@lru_cache(maxsize=2)
def configured_embedder(name: str, dimension: int, path: str):
    return build_embedder(name, dimension, path)


def get_embedder(settings: Settings = Depends(get_settings)):
    # Independent of Assessor: embedding never initializes or calls an LLM.
    try:
        return configured_embedder(settings.embedder, settings.embedding_dim, settings.embedding_model_path)
    except Exception:
        raise HTTPException(status_code=503, detail="Embedding unavailable") from None


def build_assessor(settings: Settings) -> Assessor:
    embedder = configured_embedder(
        settings.embedder, settings.embedding_dim, settings.embedding_model_path
    )

    if settings.vector_store == "pgvector":
        from psycopg_pool import ConnectionPool

        # A pool, not one long-lived connection: this service runs for days
        # and a single connection dropped by the server would take every
        # later assessment with it.
        pool = ConnectionPool(
            settings.dsn, min_size=1, max_size=4, open=True, timeout=10.0
        )
        store: Any = PgVectorStore(pool, embedder_name=embedder.name)
    elif settings.vector_store == "local":
        store = LocalVectorStore(settings.resolved_vector_store_path, embedder)
    else:
        raise RuntimeError(
            "VECTOR_STORE must be 'local' or 'pgvector' (got %r)."
            % settings.vector_store
        )

    return Assessor(settings, store, embedder, build_llm(settings))


def get_assessor() -> Assessor:
    global _assessor
    if _assessor is None:
        _assessor = build_assessor(get_settings())
    return _assessor


def require_token(
    authorization: str | None = Header(default=None),
    settings: Settings = Depends(get_settings),
) -> None:
    """Shared secret between Spring and this service. Every call to /assess
    spends money, so the route must not be open to the internet."""
    expected = settings.ai_service_token
    if not expected:
        return
    if authorization != "Bearer " + expected:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid service token"
        )


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}



@app.post("/assess", response_model=AssessResponse, dependencies=[Depends(require_token)])
async def assess(request: AssessRequest) -> AssessResponse:
    return await get_assessor().assess(request)


class EmbedRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    texts: list[Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=8000)]] = Field(
        min_length=1, max_length=100
    )


class EmbedResponse(BaseModel):
    embedder: str
    dimension: int
    vectors: list[list[float]]


@app.post("/internal/embed", response_model=EmbedResponse, dependencies=[Depends(require_token)])
def embed(request: EmbedRequest, embedder=Depends(get_embedder)) -> EmbedResponse:
    """Bounded ingestion batch, using the very same vector space as retrieval."""
    try:
        vectors = (embedder.embed_many(request.texts) if hasattr(embedder, "embed_many")
                   else [embedder.embed(text) for text in request.texts])
        if len(vectors) != len(request.texts) or any(
            len(v) != embedder.dim or not all(math.isfinite(x) for x in v)
            or not any(x != 0 for x in v) for v in vectors
        ):
            raise ValueError("Invalid embedding output")
        return EmbedResponse(embedder=embedder.name, dimension=embedder.dim, vectors=vectors)
    except Exception:
        raise HTTPException(status_code=503, detail="Embedding unavailable") from None
