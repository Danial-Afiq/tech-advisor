"""FastAPI entry point.

One route that matters: POST /assess, called by Spring Boot once per
(user_device, candidate_product) pair that has cleared the maturity gate.

The service is stateless and holds no database credentials. It never writes to
`recommendations` or `system_log`; where section 10 calls for a log row, the
row is returned in the response for Spring to persist, so the failure and the
recommendation it belongs to are written in one place.
"""

from __future__ import annotations

import logging

from fastapi import Depends, FastAPI, Header, HTTPException, status

from app.assess import Assessor
from app.config import Settings, get_settings
from app.llm import AnthropicLlm
from app.retrieval.embedder import DeterministicEmbedder
from app.retrieval.store import LocalVectorStore
from app.schemas import AssessRequest, AssessResponse

logging.basicConfig(level=logging.INFO)

app = FastAPI(
    title="Tech Advisor AI layer",
    description="Evidence grading and summary generation over owner reviews.",
    version="0.1.0",
)

_assessor: Assessor | None = None


def build_assessor(settings: Settings) -> Assessor:
    embedder = DeterministicEmbedder(dim=settings.embedding_dim)
    if settings.vector_store == "pgvector":
        # Deliberately unreachable for now. pgvector is not enabled and
        # `review_chunks` does not exist; PgVectorStore is kept so switching
        # is a config change rather than a rewrite.
        raise RuntimeError(
            "VECTOR_STORE=pgvector is not wired up yet: the vector extension "
            "and review_chunks table do not exist. Use VECTOR_STORE=local."
        )
    store = LocalVectorStore(settings.vector_store_path, embedder)
    return Assessor(settings, store, embedder, AnthropicLlm(settings))


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
