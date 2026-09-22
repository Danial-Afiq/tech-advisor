"""Section 8 checks in isolation."""

from __future__ import annotations

import json

import pytest

from app.validation import ValidationFailure, parse_response, validate

REFS = {"P1", "P2", "P3"}


def response(**overrides):
    payload = {
        "evidence_grade": "B",
        "evidence_findings": [
            {
                "factor": "battery",
                "stance": "POSITIVE",
                "supporting_refs": ["P1"],
                "note": "Owners report a full day of use.",
            }
        ],
        "irrelevant_refs": [],
        "summary": "A plain-language explanation.",
    }
    payload.update(overrides)
    return payload


def test_valid_response_passes():
    assert validate(response(), REFS)["evidence_grade"] == "B"


def test_non_json_is_rejected():
    with pytest.raises(ValidationFailure, match="not valid JSON"):
        parse_response("{definitely not json")


def test_json_array_is_rejected():
    with pytest.raises(ValidationFailure, match="JSON object"):
        parse_response(json.dumps([1, 2, 3]))


@pytest.mark.parametrize("grade", ["Z", "G", "A+", "", None, "-"])
def test_invalid_grade_letter_is_rejected(grade):
    with pytest.raises(ValidationFailure, match="evidence_grade"):
        validate(response(evidence_grade=grade), REFS)


def test_grade_insufficient_is_not_a_model_output():
    """'-' is produced by code when the gate fails, never chosen by the model."""
    with pytest.raises(ValidationFailure):
        validate(response(evidence_grade="-"), REFS)


def test_factor_outside_the_closed_list_is_rejected():
    finding = response()["evidence_findings"][0] | {"factor": "screen_burn"}
    with pytest.raises(ValidationFailure, match="factor"):
        validate(response(evidence_findings=[finding]), REFS)


def test_all_twelve_factors_are_accepted():
    from app.factors import FACTORS

    findings = [
        {
            "factor": factor,
            "stance": "MIXED",
            "supporting_refs": ["P1"],
            "note": "n",
        }
        for factor in FACTORS
    ]
    assert validate(response(evidence_findings=findings), REFS)


def test_invalid_stance_is_rejected():
    finding = response()["evidence_findings"][0] | {"stance": "GREAT"}
    with pytest.raises(ValidationFailure, match="stance"):
        validate(response(evidence_findings=[finding]), REFS)


def test_hallucinated_supporting_ref_is_rejected():
    finding = response()["evidence_findings"][0] | {"supporting_refs": ["P1", "P42"]}
    with pytest.raises(ValidationFailure, match="P42"):
        validate(response(evidence_findings=[finding]), REFS)


def test_hallucinated_irrelevant_ref_is_rejected():
    with pytest.raises(ValidationFailure, match="P9"):
        validate(response(irrelevant_refs=["P9"]), REFS)


def test_empty_findings_are_rejected():
    with pytest.raises(ValidationFailure, match="evidence_findings was empty"):
        validate(response(evidence_findings=[]), REFS)


def test_empty_summary_is_rejected():
    with pytest.raises(ValidationFailure, match="summary"):
        validate(response(summary="   "), REFS)


def test_empty_note_is_rejected():
    finding = response()["evidence_findings"][0] | {"note": ""}
    with pytest.raises(ValidationFailure, match="note"):
        validate(response(evidence_findings=[finding]), REFS)
