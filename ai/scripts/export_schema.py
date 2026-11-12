"""Exports the OpenAPI schema for the Spring Boot <-> AI service contract.

Spring Boot's AssessRequest/AssessResponse (and their nested records) are
meant to be a wire-exact mirror of app.schemas.py's AssessRequest/
AssessResponse - today that's checked by a human-maintained comment in
AssessContractTests.java, which can silently drift from the real Python
shape. This script writes the real shape to contracts/ai-service-schema.json
instead, which test_schema_contract.py (this side) and AssessContractTests
(Java side) both check against.

Run after changing a field on AssessRequest/AssessResponse or anything they
reference:

    cd ai && .venv/bin/python scripts/export_schema.py

test_schema_contract.py fails if this file is out of date, with a diff of
what changed - that failure is the signal to re-run this script and review
the diff, not to hand-edit the committed file.
"""

from __future__ import annotations

import json
from pathlib import Path

from app.main import app

# Only the request/response contract this repo keeps a Java-side mirror of.
# EmbedRequest/EmbedResponse (POST /internal/embed) and FastAPI's generic
# HTTPValidationError/ValidationError are real schemas but outside that
# contract - including them would make every unrelated validation-error
# tweak look like a Spring Boot contract change.
ROOTS = ("AssessRequest", "AssessResponse")

OUTPUT = Path(__file__).resolve().parent.parent.parent / "contracts" / "ai-service-schema.json"


def _reachable(schemas: dict, roots: tuple[str, ...]) -> dict:
    """All schemas reachable from roots by following $ref, roots included."""
    seen: dict = {}
    pending = list(roots)
    while pending:
        name = pending.pop()
        if name in seen:
            continue
        schema = schemas[name]
        seen[name] = schema
        for ref in _refs(schema):
            pending.append(ref)
    return seen


def _refs(node):
    if isinstance(node, dict):
        if "$ref" in node:
            yield node["$ref"].rsplit("/", 1)[-1]
        for value in node.values():
            yield from _refs(value)
    elif isinstance(node, list):
        for item in node:
            yield from _refs(item)


def export() -> dict:
    schemas = app.openapi()["components"]["schemas"]
    return _reachable(schemas, ROOTS)


if __name__ == "__main__":
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(export(), indent=2, sort_keys=True) + "\n")
    print(f"wrote {OUTPUT}")
