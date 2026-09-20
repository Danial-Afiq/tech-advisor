"""FastAPI entry point.

One route that matters: POST /assess, called by Spring Boot once per
(user_device, candidate_product) pair that has cleared the maturity gate.

The service is stateless. With VECTOR_STORE=pgvector it holds read
credentials for review_chunks; it still writes nothing. It never writes to
`recommendations` or `system_log`; where section 10 calls for a log row, the
row is returned in the response for Spring to persist, so the failure and the
recommendation it belongs to are written in one place.
"""

from __future__ import annotations

import logging
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, status

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


def build_assessor(settings: Settings) -> Assessor:
    embedder = build_embedder(
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
