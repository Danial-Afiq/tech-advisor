"""Assessment orchestration, with the model mocked. No live calls."""

from __future__ import annotations

import json

import pytest

from app.llm import LlmError
from tests.conftest import FakeLlm, make_assessor, valid_model_response


async def test_valid_response_maps_refs_back_to_chunk_ids(
    settings, store, assess_request
):
    llm = FakeLlm([valid_model_response(grade="C", refs=["P1", "P2"])])
    response = await make_assessor(settings, store, llm).assess(assess_request)

    assert response.evidence_grade == "C"
    assert response.meta.retry_count == 0
    assert response.meta.degraded is False
    assert response.system_log is None

    # Refs are local to one request; Spring only ever sees real chunk IDs.
    finding = response.evidence_findings[0]
    assert finding.supporting_refs == ["P1", "P2"]
    assert finding.supporting_chunk_ids == response.meta.retrieved_chunk_ids[:2]
    assert all(isinstance(cid, int) for cid in finding.supporting_chunk_ids)

    # Every retrieved passage is recorded, not only the supporting ones.
    assert len(response.meta.retrieved_chunk_ids) == 4
    assert set(response.meta.retrieved_chunk_ids) <= {4412, 4418, 4420, 4425}


async def test_hallucinated_ref_is_rejected(settings, store, assess_request):
    bad = valid_model_response(refs=["P99"])
    good = valid_model_response(refs=["P1"])
    llm = FakeLlm([bad, good])

    response = await make_assessor(settings, store, llm).assess(assess_request)

    assert len(llm.calls) == 2
    assert response.meta.retry_count == 1
    assert response.evidence_grade == "C"
    retry_message = llm.calls[1]["messages"][-1]["content"]
    assert "P99" in retry_message


async def test_retry_fires_once_and_only_once(settings, store, assess_request):
    bad = valid_model_response(refs=["P99"])
    llm = FakeLlm([bad, bad])

    response = await make_assessor(settings, store, llm).assess(assess_request)

    assert len(llm.calls) == 2, "MAX_RETRIES=1 means at most two calls"
    assert response.evidence_grade == "-"
    assert response.meta.degraded is True
    assert response.meta.degraded_reason == "VALIDATION_FAILED"
    assert response.summary is None
    assert response.system_log["component"] == "recommendation_ai"
    assert response.system_log["status"] == "FAILURE"
    assert response.system_log["metadata"]["retry_count"] == 1
    assert response.system_log["metadata"]["request_id"] == assess_request.request_id


@pytest.mark.parametrize(
    "broken",
    [
        "not json at all",
        json.dumps({"evidence_grade": "Z", "evidence_findings": [], "summary": "x"}),
        json.dumps(
            {
                "evidence_grade": "B",
                "evidence_findings": [
                    {
                        "factor": "vibes",
                        "stance": "POSITIVE",
                        "supporting_refs": ["P1"],
                        "note": "n",
                    }
                ],
                "irrelevant_refs": [],
                "summary": "x",
            }
        ),
    ],
)
async def test_structurally_invalid_responses_degrade(
    settings, store, assess_request, broken
):
    llm = FakeLlm([broken, broken])
    response = await make_assessor(settings, store, llm).assess(assess_request)

    assert response.evidence_grade == "-"
    assert response.meta.degraded_reason == "VALIDATION_FAILED"


async def test_empty_store_returns_insufficient_evidence(
    settings, empty_store, assess_request
):
    llm = FakeLlm([])
    response = await make_assessor(settings, empty_store, llm).assess(assess_request)

    assert llm.calls == [], "no passages means no reason to spend a call"
    assert response.evidence_grade == "-"
    assert response.meta.degraded_reason == "NO_PASSAGES_RETRIEVED"
    assert response.meta.retrieved_chunk_ids == []


async def test_llm_failure_degrades_without_fabricating(
    settings, store, assess_request
):
    llm = FakeLlm([LlmError("upstream exploded")])
    response = await make_assessor(settings, store, llm).assess(assess_request)

    assert response.evidence_grade == "-"
    assert response.evidence_findings == []
    assert response.summary is None
    assert response.meta.degraded_reason == "LLM_CALL_FAILED"


async def test_mostly_irrelevant_passages_produce_no_grade(
    settings, store, assess_request
):
    """Code decides whether there is enough evidence, never the model."""
    llm = FakeLlm(
        [valid_model_response(refs=["P1"], irrelevant=["P1", "P2", "P3", "P4"])]
    )
    response = await make_assessor(settings, store, llm).assess(assess_request)

    assert response.evidence_grade == "-"
    assert response.meta.degraded_reason == "INSUFFICIENT_RELEVANT_PASSAGES"


async def test_meta_records_retrieval_parameters(settings, store, assess_request):
    llm = FakeLlm([valid_model_response()])
    response = await make_assessor(settings, store, llm).assess(assess_request)

    assert response.meta.prompt_version == "v1"
    assert response.meta.ai_model == "fake-model"
    assert response.meta.retrieval["chunk_char_cap"] == settings.chunk_char_cap
    assert response.meta.retrieval["vector_store"] == "local"
