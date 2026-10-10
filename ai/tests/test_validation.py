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


@pytest.mark.parametrize("grade", ["Z", "G", "A+", "", None, "-", 0.82, []])
def test_invalid_grade_letter_is_rejected(grade):
    with pytest.raises(ValidationFailure, match="evidence_grade"):
        validate(response(evidence_grade=grade), REFS)


def test_grade_insufficient_is_not_a_model_output():
    """'-' is produced by code when the gate fails, never chosen by the model."""
    with pytest.raises(ValidationFailure):
        validate(response(evidence_grade="-"), REFS)


@pytest.mark.parametrize("grade", list("ABCDEF"))
def test_every_canonical_evidence_grade_is_accepted(grade):
    assert validate(response(evidence_grade=grade), REFS)["evidence_grade"] == grade


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
    with pytest.raises(ValidationFailure, match="evidence_findings"):
        validate(response(evidence_findings=[]), REFS)


def test_empty_summary_is_rejected():
    with pytest.raises(ValidationFailure, match="summary"):
        validate(response(summary="   "), REFS)


def test_empty_note_is_rejected():
    finding = response()["evidence_findings"][0] | {"note": ""}
    with pytest.raises(ValidationFailure, match="note"):
        validate(response(evidence_findings=[finding]), REFS)


@pytest.mark.parametrize("field", ["evidence_grade", "evidence_findings", "irrelevant_refs", "summary"])
def test_every_top_level_field_is_required(field):
    payload = response()
    del payload[field]
    with pytest.raises(ValidationFailure, match=field):
        validate(payload, REFS)


@pytest.mark.parametrize("field", ["factor", "stance", "supporting_refs", "note"])
def test_every_finding_field_is_required(field):
    payload = response()
    del payload["evidence_findings"][0][field]
    with pytest.raises(ValidationFailure, match=field):
        validate(payload, REFS)


@pytest.mark.parametrize("value", [42, True, ["text"], {"text": "explanation"}, None, "\ud800"])
@pytest.mark.parametrize("field", ["summary", "note"])
def test_prose_must_be_a_string_without_coercion(field, value):
    payload = response()
    target = payload if field == "summary" else payload["evidence_findings"][0]
    target[field] = value
    with pytest.raises(ValidationFailure, match=field):
        validate(payload, REFS)


@pytest.mark.parametrize("nested", [False, True])
def test_unexpected_fields_are_rejected(nested):
    payload = response()
    target = payload["evidence_findings"][0] if nested else payload
    target["verdict"] = "STRONG_UPGRADE_CANDIDATE"
    with pytest.raises(ValidationFailure, match="verdict"):
        validate(payload, REFS)


@pytest.mark.parametrize("refs", [[], "P1", [1], [None], [["P1"]], ["P1", "P1"]])
def test_unsupported_or_malformed_supporting_refs_are_rejected(refs):
    payload = response()
    payload["evidence_findings"][0]["supporting_refs"] = refs
    with pytest.raises(ValidationFailure, match="supporting_refs"):
        validate(payload, REFS)


def test_a_finding_cannot_cite_an_irrelevant_passage():
    with pytest.raises(ValidationFailure, match="irrelevant_refs"):
        validate(response(irrelevant_refs=["P1"]), REFS)


def test_duplicate_irrelevant_refs_are_rejected():
    with pytest.raises(ValidationFailure, match="duplicate"):
        validate(response(irrelevant_refs=["P2", "P2"]), REFS)


@pytest.mark.parametrize("raw", [
    '{"summary":"first","summary":"second"}',
    '{"finding":{"note":"first","note":"second"}}',
    '{"value":NaN}', '{"value":Infinity}', '{"value":-Infinity}',
    '```json\n{}\n```', None,
])
def test_ambiguous_or_non_json_output_is_rejected(raw):
    with pytest.raises(ValidationFailure):
        parse_response(raw)


def test_decoder_recursion_errors_are_validation_failures(monkeypatch):
    # Decoder depth limits differ across supported Python versions.
    def fail_at_depth(*args, **kwargs):
        raise RecursionError("JSON nesting exceeded the decoder limit")

    monkeypatch.setattr(json, "loads", fail_at_depth)
    with pytest.raises(ValidationFailure, match="not valid JSON"):
        parse_response("{}")
