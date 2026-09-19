"""The HTTP surface Spring Boot talks to."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app import main
from app.config import get_settings
from app.schemas import AssessResponse, ResponseMeta


class StubAssessor:
    def __init__(self) -> None:
        self.seen = []

    async def assess(self, request):
        self.seen.append(request)
        return AssessResponse(
            request_id=request.request_id,
            evidence_grade="B",
            summary="Stubbed.",
            meta=ResponseMeta(
                ai_model="fake-model",
                prompt_version="v1",
                retrieved_chunk_ids=[1, 2],
                retry_count=0,
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
