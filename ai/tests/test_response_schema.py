"""The service envelope cannot present an unavailable assessment as success."""

import pytest
from pydantic import ValidationError

from app.schemas import AssessResponse


def success_payload():
    return {
        "request_id": "req-1",
        "evidence_grade": "B",
        "evidence_findings": [{
            "factor": "battery", "stance": "POSITIVE", "supporting_refs": ["P1"],
            "supporting_chunk_ids": [4412], "note": "Owners report good endurance.",
        }],
        "summary": "Owners report good endurance.",
        "meta": {
            "ai_model": "fake-model", "prompt_version": "v1", "retry_count": 0,
            "retrieved_chunk_ids": [4412],
            "retrieval": {"k": 12, "chunk_char_cap": 800, "vector_store": "local", "embedding_dim": 512},
        },
    }


@pytest.mark.parametrize("field,value", [
    ("ai_model", " "), ("prompt_version", 42), ("retrieved_chunk_ids", [True]),
    ("retry_count", 2), ("retry_count", "1"), ("degraded", "false"),
    ("degraded_reason", "VALIDATION_FAILED"),
])
def test_invalid_metadata_is_rejected(field, value):
    payload = success_payload()
    payload["meta"][field] = value
    with pytest.raises(ValidationError):
        AssessResponse.model_validate(payload)


@pytest.mark.parametrize("field,value", [
    ("k", 0), ("chunk_char_cap", "800"), ("embedding_dim", -1), ("vector_store", "unknown"),
])
def test_invalid_retrieval_metadata_is_rejected(field, value):
    payload = success_payload()
    payload["meta"]["retrieval"][field] = value
    with pytest.raises(ValidationError):
        AssessResponse.model_validate(payload)


@pytest.mark.parametrize("field,value", [
    ("evidence_grade", "-"), ("evidence_findings", []), ("summary", None),
    ("system_log", {"status": "FAILURE"}),
])
def test_success_requires_a_complete_assessment(field, value):
    payload = success_payload()
    payload[field] = value
    with pytest.raises(ValidationError):
        AssessResponse.model_validate(payload)


def test_degraded_response_cannot_keep_model_prose_or_findings():
    payload = success_payload()
    payload["meta"].update(degraded=True, degraded_reason="VALIDATION_FAILED")
    payload["system_log"] = {"component": "recommendation_ai", "status": "FAILURE"}
    with pytest.raises(ValidationError):
        AssessResponse.model_validate(payload)

    payload.update(evidence_grade="-", evidence_findings=[], summary=None)
    assert AssessResponse.model_validate(payload).meta.degraded
    payload["system_log"] = None
    with pytest.raises(ValidationError):
        AssessResponse.model_validate(payload)
