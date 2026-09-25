"""Manual, human-judgment evaluation of the /assess summary output.

Calls the Assessor in-process (no FastAPI, no auth token) against the fixture
corpus in `ai/manual_eval/fixtures/`, so a full pass runs in seconds and needs
nothing running besides an LLM credential in the root `.env`.

    python -m scripts.manual_eval                  # every case
    python -m scripts.manual_eval 701_clear_positive 704_injection_attempt

Settings come from the root `.env` as usual, including the embedder, so
passages are ranked exactly as the running service ranks them. The only thing
pinned here is the vector store: the fixture directory below, rather than
pgvector.

This is not a substitute for `pytest`: nothing here asserts. It exists so a
person can read each summary against its source passages and judge whether it
is grounded, on-topic, and appropriately confident - see
`ai/manual_eval/README.md` for the checklist.
"""

from __future__ import annotations

import asyncio
import json
import logging
import sys
from datetime import datetime
from pathlib import Path
from typing import Any

AI_ROOT = Path(__file__).resolve().parents[1]
CASES_DIR = AI_ROOT / "manual_eval" / "cases"
FIXTURES_DIR = AI_ROOT / "manual_eval" / "fixtures"

TRANSCRIPT = AI_ROOT / "manual_eval" / "last_run.txt"

# Assessor logs a warning whenever it degrades or retries. Unlabelled, those
# read like something the model said, so they get a prefix. WARNING rather
# than INFO keeps the openai and httpx request chatter out.
LOG_FORMAT = "[service-log | %(name)s | %(levelname)s] %(message)s"

sys.path.insert(0, str(AI_ROOT))

from app.assess import Assessor  # noqa: E402
from app.config import Settings  # noqa: E402
from app.llm import build_llm  # noqa: E402
from app.retrieval.embedder import build_embedder  # noqa: E402
from app.retrieval.store import LocalVectorStore  # noqa: E402
from app.schemas import AssessRequest  # noqa: E402

WIDTH = 88


def build_manual_assessor(settings: Settings) -> Assessor:
    # The configured embedder, not the deterministic stand-in the tests use:
    # ranking the fixture passages the way production ranks real ones is the
    # point here, and a hermetic run is not.
    embedder = build_embedder(
        settings.embedder, settings.embedding_dim, settings.embedding_model_path
    )
    store = LocalVectorStore(FIXTURES_DIR, embedder)
    return Assessor(settings, store, embedder, build_llm(settings))


def resolve_cases(names: list[str]) -> list[Path]:
    if not names:
        return sorted(CASES_DIR.glob("*.json"))
    paths = []
    for name in names:
        stem = name[:-5] if name.endswith(".json") else name
        paths.append(CASES_DIR / (stem + ".json"))
    return paths


async def run_case(assessor: Assessor, path: Path) -> None:
    request = AssessRequest.model_validate(
        json.loads(path.read_text(encoding="utf-8"))
    )
    response = await assessor.assess(request)

    print("=" * WIDTH)
    print(f"CASE {path.stem}  (product_id={request.candidate.product_id})")
    print(f"deciding_factors : {request.analysis.deciding_factors}")
    print(f"priorities       : {dict(request.user_context.preferences.priorities)}")
    print(f"verdict fed in   : {request.analysis.verdict}"
          f"  (upgrade_score {request.analysis.upgrade_score})")
    print("-" * WIDTH)
    print(f"evidence_grade : {response.evidence_grade}")
    if response.meta.retry_count:
        print(f"retries        : {response.meta.retry_count} (model failed schema validation)")
    if response.meta.degraded:
        print(f"DEGRADED       : {response.meta.degraded_reason}")
    for finding in response.evidence_findings:
        print(f"  [{finding.factor:16s}] {finding.stance:8s} refs={finding.supporting_refs}")
        print(f"      note: {finding.note}")
    if response.irrelevant_refs:
        print(f"irrelevant_refs: {response.irrelevant_refs}")
    print("-" * WIDTH)
    print("SUMMARY:")
    print(response.summary or "(none - degraded, no summary returned)")
    print()


class _Tee:
    """Console and transcript at once, so a run is watchable while it happens
    and still leaves a file to hand to someone else."""

    def __init__(self, *streams: Any) -> None:
        self.streams = streams

    def write(self, text: str) -> int:
        for stream in self.streams:
            stream.write(text)
        return len(text)

    def flush(self) -> None:
        for stream in self.streams:
            stream.flush()


async def main(argv: list[str]) -> int:
    cases = resolve_cases(argv)
    for path in cases:
        if not path.exists():
            print(f"skipping (not found): {path.name}")
    cases = [path for path in cases if path.exists()]
    if not cases:
        print(f"no cases found in {CASES_DIR}")
        return 1

    settings = Settings(vector_store="local")
    assessor = build_manual_assessor(settings)

    with TRANSCRIPT.open("w", encoding="utf-8") as transcript:
        console = sys.stdout
        sys.stdout = _Tee(console, transcript)
        # Logging is attached to the tee too, and detached again below: leaving
        # a handler pointed at a closed file breaks logging.shutdown() at exit.
        handler = logging.StreamHandler(sys.stdout)
        handler.setFormatter(logging.Formatter(LOG_FORMAT))
        root = logging.getLogger()
        root.setLevel(logging.WARNING)
        root.addHandler(handler)
        try:
            print(f"run at        : {datetime.now().isoformat(timespec='seconds')}")
            print(f"provider      : {settings.llm_provider}")
            print(f"model         : {settings.llm_model}")
            print(f"prompt_version: {settings.prompt_version}")
            print(f"fixtures      : {FIXTURES_DIR}")
            print(f"running {len(cases)} case(s)\n")
            for path in cases:
                await run_case(assessor, path)
        finally:
            root.removeHandler(handler)
            sys.stdout = console

    print(f"transcript written to {TRANSCRIPT}")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main(sys.argv[1:])))
