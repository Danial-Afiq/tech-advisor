"""Output validation. Section 8.

Runs before anything reaches Spring. Every failure here is structural and is
handed back to the model once, verbatim, as the retry instruction.

The API is also given the same shape as a JSON schema, so most of these checks
should never fire in production. They stay because the schema cannot express
the one check that matters most: a ref the model invented is indistinguishable
from a real one to any schema validator, and it is the failure that would
otherwise silently attribute a grade to evidence that was never retrieved.
"""

from __future__ import annotations

import json
from typing import Any

from app.factors import FACTORS, GRADES, STANCES


class ValidationFailure(Exception):
    """Structural problem with a model response. The message is fed back to
    the model on the single permitted retry, so it must describe the fault
    precisely and say nothing else."""


def parse_response(raw: str) -> dict[str, Any]:
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise ValidationFailure("Response was not valid JSON: %s" % exc) from exc
    if not isinstance(parsed, dict):
        raise ValidationFailure("Response must be a JSON object.")
    return parsed


def validate(parsed: dict[str, Any], known_refs: set[str]) -> dict[str, Any]:
    grade = parsed.get("evidence_grade")
    if grade not in GRADES:
        raise ValidationFailure(
            "evidence_grade was %r; it must be one of %s."
            % (grade, ", ".join(GRADES))
        )

    findings = parsed.get("evidence_findings")
    if not isinstance(findings, list):
        raise ValidationFailure("evidence_findings must be a list.")
    if not findings:
        raise ValidationFailure(
            "evidence_findings was empty. A grade must rest on at least one "
            "finding; if no passage supports a finding, list the passages in "
            "irrelevant_refs."
        )

    for index, finding in enumerate(findings):
        if not isinstance(finding, dict):
            raise ValidationFailure("evidence_findings[%d] must be an object." % index)
        factor = finding.get("factor")
        if factor not in FACTORS:
            raise ValidationFailure(
                "evidence_findings[%d].factor was %r; it must be one of %s."
                % (index, factor, ", ".join(FACTORS))
            )
        stance = finding.get("stance")
        if stance not in STANCES:
            raise ValidationFailure(
                "evidence_findings[%d].stance was %r; it must be one of %s."
                % (index, stance, ", ".join(STANCES))
            )
        if not str(finding.get("note") or "").strip():
            raise ValidationFailure(
                "evidence_findings[%d].note was empty." % index
            )
        refs = finding.get("supporting_refs")
        if not isinstance(refs, list):
            raise ValidationFailure(
                "evidence_findings[%d].supporting_refs must be a list." % index
            )
        _check_refs(
            refs, known_refs, "evidence_findings[%d].supporting_refs" % index
        )

    irrelevant = parsed.get("irrelevant_refs", [])
    if not isinstance(irrelevant, list):
        raise ValidationFailure("irrelevant_refs must be a list.")
    _check_refs(irrelevant, known_refs, "irrelevant_refs")

    if not str(parsed.get("summary") or "").strip():
        raise ValidationFailure("summary was empty.")

    return parsed


def _check_refs(refs: list[Any], known_refs: set[str], field: str) -> None:
    for ref in refs:
        if not isinstance(ref, str) or ref not in known_refs:
            # Never coerce or silently drop: a ref that was not sent is a
            # hallucination, and dropping it would leave the grade standing on
            # evidence that does not exist.
            raise ValidationFailure(
                "%s contains %r, which was not one of the passages provided. "
                "Use only the refs shown in the owner reports block." % (field, ref)
            )
