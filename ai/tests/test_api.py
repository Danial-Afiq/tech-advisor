"""The HTTP surface Spring Boot talks to."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app import main
from app.config import get_settings
from app.schemas import AssessResponse, EvidenceFinding, ResponseMeta
from tests.conftest import FakeLlm, make_assessor, valid_model_response


class StubAssessor:
    def __init__(self) -> None:
        self.seen = []

    async def assess(self, request):
        self.seen.append(request)
        return AssessResponse(
            request_id=request.request_id,
            evidence_grade="B",
            evidence_findings=[EvidenceFinding(
                factor="battery", stance="POSITIVE", supporting_refs=["P1"],
                supporting_chunk_ids=[1], note="Owners report good endurance.",
            )],
            summary="Stubbed.",
            meta=ResponseMeta(
                ai_model="fake-model",
                prompt_version="v1",
                retrieved_chunk_ids=[1, 2],
                retry_count=0,
                retrieval={"k": 4, "chunk_char_cap": 200, "vector_store": "local", "embedding_dim": 64},
            ),
        )


@pytest.fixture
def client(settings, monkeypatch):
    stub = StubAssessor()
    monkeypatch.setattr(main, "_assessor", stub)
    main.app.dependency_overrides[get_settings] = lambda: settings
    yield TestClient(main.app), stub, settings
    main.app.dependency_overrides.clear()


def test_health(client):
    http, _, _ = client
    assert http.get("/health").json() == {"status": "ok"}


def test_assess_returns_the_contract(client, request_payload):
    http, stub, _ = client
    response = http.post("/assess", json=request_payload)

    assert response.status_code == 200
    body = response.json()
    assert body["evidence_grade"] == "B"
    assert body["request_id"] == request_payload["request_id"]
    assert body["meta"]["retrieved_chunk_ids"] == [1, 2]
    assert stub.seen[0].candidate.product_id == 812


def test_token_is_required_when_configured(client, request_payload):
    http, _, settings = client
    settings.ai_service_token = "s3cret"

    assert http.post("/assess", json=request_payload).status_code == 401
    ok = http.post(
        "/assess",
        json=request_payload,
        headers={"Authorization": "Bearer s3cret"},
    )
    assert ok.status_code == 200


def test_unknown_factor_in_priorities_is_rejected(client, request_payload):
    http, _, _ = client
    request_payload["user_context"]["preferences"]["priorities"]["vibes"] = 5

    assert http.post("/assess", json=request_payload).status_code == 422


def test_unknown_enum_value_is_rejected(client, request_payload):
    http, _, _ = client
    request_payload["user_context"]["preferences"]["upgrade_urgency"] = "MAYBE"

    assert http.post("/assess", json=request_payload).status_code == 422


@pytest.mark.parametrize("verdict", ["BUY_NOW", "worth_watching", 42, None])
def test_noncanonical_verdict_is_rejected_before_generation(client, request_payload, verdict):
    http, stub, _ = client
    request_payload["analysis"]["verdict"] = verdict
    assert http.post("/assess", json=request_payload).status_code == 422
    assert stub.seen == []


@pytest.mark.parametrize("score", [-0.01, 1.01, "0.7", True, None])
def test_invalid_score_is_rejected_before_generation(client, request_payload, score):
    http, stub, _ = client
    request_payload["analysis"]["upgrade_score"] = score
    assert http.post("/assess", json=request_payload).status_code == 422
    assert stub.seen == []


@pytest.mark.parametrize("score", [0, 1])
def test_score_range_includes_both_boundaries(client, request_payload, score):
    http, _, _ = client
    request_payload["analysis"]["upgrade_score"] = score
    assert http.post("/assess", json=request_payload).status_code == 200


@pytest.mark.parametrize("recovery", [False, True])
def test_http_assessment_uses_real_validation_with_a_mocked_llm(
    client, monkeypatch, store, request_payload, recovery
):
    http, _, settings = client
    llm = FakeLlm(['{"summary":42}', valid_model_response() if recovery else "not JSON"])
    monkeypatch.setattr(main, "_assessor", make_assessor(settings, store, llm))

    response = http.post("/assess", json=request_payload)
    assert response.status_code == 200
    body = response.json()
    assert len(llm.calls) == 2
    assert body["meta"]["retry_count"] == 1
    assert body["meta"]["degraded"] is not recovery
    if recovery:
        assert body["evidence_grade"] == "C"
        assert body["summary"]
        assert body["system_log"] is None
    else:
        assert body["evidence_grade"] == "-"
        assert body["summary"] is None
        assert body["evidence_findings"] == []
        assert body["meta"]["degraded_reason"] == "VALIDATION_FAILED"
        assert body["system_log"]["status"] == "FAILURE"
