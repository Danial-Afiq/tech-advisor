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

from pydantic import ValidationError

from app.schemas import ModelAssessment


class ValidationFailure(Exception):
    """Structural problem with a model response. The message is fed back to
    the model on the single permitted retry, so it must describe the fault
    precisely and say nothing else."""


def parse_response(raw: str) -> dict[str, Any]:
    if not isinstance(raw, str):
        raise ValidationFailure("Response must be JSON text.")
    try:
        parsed = json.loads(
            raw, object_pairs_hook=_unique_object, parse_constant=_reject_constant
        )
    except (json.JSONDecodeError, RecursionError) as exc:
        raise ValidationFailure("Response was not valid JSON: %s" % exc) from exc
    if not isinstance(parsed, dict):
        raise ValidationFailure("Response must be a JSON object.")
    return parsed


def validate(parsed: dict[str, Any], known_refs: set[str]) -> dict[str, Any]:
    try:
        assessment = ModelAssessment.model_validate(parsed)
    except ValidationError as exc:
        # Feedback contains field paths and expected types, never rejected
        # prose. Every schema failure stays inside the single-retry loop.
        details = "; ".join(
            "%s: %s" % (".".join(map(str, error["loc"])), error["msg"])
            for error in exc.errors(include_input=False, include_url=False)
        )
        raise ValidationFailure(details) from exc

    _check_refs(assessment.irrelevant_refs, known_refs, "irrelevant_refs")
    irrelevant = set(assessment.irrelevant_refs)
    for index, finding in enumerate(assessment.evidence_findings):
        field = "evidence_findings[%d].supporting_refs" % index
        _check_refs(finding.supporting_refs, known_refs, field)
        if irrelevant.intersection(finding.supporting_refs):
            raise ValidationFailure(
                "%s cannot cite a passage listed in irrelevant_refs." % field
            )

    return assessment.model_dump()


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    parsed: dict[str, Any] = {}
    for key, value in pairs:
        if key in parsed:
            raise ValidationFailure("Response contains duplicate JSON fields.")
        parsed[key] = value
    return parsed


def _reject_constant(value: str) -> None:
    raise ValidationFailure("Response was not valid JSON: %s is not permitted." % value)


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
    if len(refs) != len(set(refs)):
        raise ValidationFailure("%s contains duplicate passage refs." % field)
