"""The Spring Boot <-> AI service wire contract must not drift silently.

Regenerates the same schema scripts/export_schema.py writes and compares it
to the committed copy. A failure here means AssessRequest/AssessResponse (or
something they reference) changed shape without the committed snapshot
being regenerated - run `python scripts/export_schema.py`, review the diff,
commit it, and update the Java side (AssessContractTests /
AssessRequest.java / AssessResponse.java) to match before this can pass.
"""

from __future__ import annotations

import json
from pathlib import Path

from scripts.export_schema import OUTPUT, export


def test_committed_schema_matches_the_real_contract():
    committed = json.loads(OUTPUT.read_text())
    current = export()

    assert current == committed, (
        "contracts/ai-service-schema.json is out of date - run "
        "`cd ai && .venv/bin/python scripts/export_schema.py`, review the diff, "
        "commit it, and update the Java side to match."
    )
