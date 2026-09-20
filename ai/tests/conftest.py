from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from app.assess import Assessor
from app.config import Settings
from app.llm import LlmError, LlmResult
from app.retrieval.embedder import DeterministicEmbedder
from app.retrieval.store import LocalVectorStore
from app.schemas import AssessRequest

FIXTURES = Path(__file__).parent / "fixtures"


class FakeLlm:
    """Records every call and replays scripted responses.

    A response may be a string (returned as model text) or an exception
    (raised). The call count is what the retry tests assert on.
    """

    def __init__(self, responses: list[Any]) -> None:
        self.responses = list(responses)
        self.calls: list[dict[str, Any]] = []

    async def complete(
        self, system: str, messages: list[dict[str, Any]], schema: dict
    ) -> LlmResult:
        self.calls.append({"system": system, "messages": messages, "schema": schema})
        if not self.responses:
            raise AssertionError("FakeLlm called more times than scripted")
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return LlmResult(text=response, model="fake-model")

    @property
    def last_user_prompt(self) -> str:
        return self.calls[-1]["messages"][0]["content"]


@pytest.fixture(autouse=True)
def isolate_settings_env(monkeypatch: pytest.MonkeyPatch) -> None:
    """Keep the suite hermetic.

    pydantic-settings ranks the process environment above any env file, so a
    developer who exports LLM_MODEL or ANTHROPIC_API_KEY - or just sources the
    root .env - would silently change what these tests assert. Clearing every
    variable matching a Settings field means assertions describe the code and
    the fixtures, never the machine they run on.
    """
    for name in Settings.model_fields:
        monkeypatch.delenv(name.upper(), raising=False)


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    # `_env_file=None` keeps the suite hermetic. Without it, any field not
    # pinned below is inherited from the developer's root .env, so switching
    # LLM_PROVIDER locally would change what the tests assert.
    return Settings(
        _env_file=None,
        llm_provider="anthropic",
        llm_model="claude-opus-5",
        llm_schema_mode="auto",
        ai_service_token="",
        k=4,
        chunk_char_cap=200,
        prompt_version="v1",
        max_retries=1,
        vector_store="local",
        vector_store_path=str(tmp_path / "store"),
        embedding_dim=64,
        irrelevant_ref_limit=0.75,
    )


@pytest.fixture
def chunks() -> list[dict[str, Any]]:
    return json.loads((FIXTURES / "chunks.json").read_text(encoding="utf-8"))


@pytest.fixture
def store(settings: Settings, chunks: list[dict[str, Any]]) -> LocalVectorStore:
    path = Path(settings.vector_store_path)
    path.mkdir(parents=True, exist_ok=True)
    (path / "chunks.json").write_text(json.dumps(chunks), encoding="utf-8")
    return LocalVectorStore(path, DeterministicEmbedder(dim=settings.embedding_dim))


@pytest.fixture
def empty_store(settings: Settings) -> LocalVectorStore:
    return LocalVectorStore(
        settings.vector_store_path, DeterministicEmbedder(dim=settings.embedding_dim)
    )


@pytest.fixture
def request_payload() -> dict[str, Any]:
    return json.loads((FIXTURES / "assess_request.json").read_text(encoding="utf-8"))


@pytest.fixture
def assess_request(request_payload: dict[str, Any]) -> AssessRequest:
    return AssessRequest.model_validate(request_payload)


def make_assessor(settings: Settings, store: Any, llm: Any) -> Assessor:
    return Assessor(
        settings, store, DeterministicEmbedder(dim=settings.embedding_dim), llm
    )


def valid_model_response(
    grade: str = "C",
    refs: list[str] | None = None,
    irrelevant: list[str] | None = None,
) -> str:
    return json.dumps(
        {
            "evidence_grade": grade,
            "evidence_findings": [
                {
                    "factor": "battery",
                    "stance": "NEGATIVE",
                    "supporting_refs": refs if refs is not None else ["P1"],
                    "note": "Owners past six months report reduced endurance.",
                }
            ],
            "irrelevant_refs": irrelevant or [],
            "summary": "Owner reports are mixed on the factors you care about.",
        }
    )


__all__ = [
    "FakeLlm",
    "LlmError",
    "make_assessor",
    "valid_model_response",
]
