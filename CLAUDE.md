# CLAUDE.md

Before working on this repository, read `/AGENTS.md` completely.

`AGENTS.md` is the shared Tech Advisor project context and source of truth for:
- architecture and component responsibilities
- database design
- AI / LLM design
- ingestion design
- deployment and CI/CD
- Jira / Git workflow
- current implementation state
- deprecated ideas
- unresolved decisions
- conflict-resolution / source-of-truth rules

Follow `AGENTS.md` unless the user explicitly approves a new project decision.

Additional rules:
1. Inspect the current source code before claiming something is implemented.
2. Do not revive deprecated designs documented in `AGENTS.md`.
3. Do not invent unresolved project decisions; keep them configurable or ask when the choice materially affects architecture.
4. When proposing a major architectural or schema change, compare it against `AGENTS.md` first and call out any conflict.
5. If a project decision changes, update `AGENTS.md` alongside the related implementation/docs where practical.

## Working in the Python AI layer (`ai/`)

See `AGENTS.md` §5.4, §13.5 and §18.7 for what this service is and its current state.

Practical rules for this directory:

1. **Use the venv.** `ai/.venv/Scripts/python.exe` on Windows. A bare `python` will
   not have `pydantic`, `fastapi` or `model2vec` and fails on import.
2. **Never spend API credits without explicit permission.** `python -m
   scripts.manual_eval` makes one billed live model call per non-degraded case
   against the key in the root `.env`. Ask first, every time — approval for one run
   is not approval for the next. `ai/tests/` is mocked and always free to run.
3. **`EMBEDDER` must match what ingested the corpus.** A mismatch does not error at
   the vector level, it returns confidently ranked nonsense. `DeterministicEmbedder`
   is a test stand-in only and must never touch a real corpus.
4. **The model does not decide the verdict**, and it does not decide whether evidence
   is sufficient. Both are code's job. If a change would move either judgement into
   the prompt, stop and raise it.
5. **Do not assert on a specific letter grade** in tests or docs. The same corpus can
   produce an adjacent grade on a rerun (`AGENTS.md` §13.5).
6. **Closed vocabularies live in `ai/app/factors.py`.** Changing a factor means
   changing the Java preference vocabulary, validation and tests together.

## Mandatory pre-review / pre-merge maintenance

Before handing a completed feature/task to the user for review, PR, or merge:

1. Re-read the relevant parts of `/AGENTS.md`.
2. Compare them against what was **actually implemented**, not merely the original plan.
3. If the implementation changes shared project context, update `/AGENTS.md` in the same branch/PR.
4. If the implementation differs from the original plan, make the implemented design current and mark the old plan superseded/deprecated where useful.
5. If no shared context changed, do not create unnecessary edits; explicitly state that `AGENTS.md` was reviewed and no update was needed.

Shared-context changes include architecture, schema, API contracts, AI/RAG behaviour, ingestion, dependencies/versions, environment/configuration, deployment/CI/CD, authentication, workflow, important UI/product behaviour, resolved decisions, and planned-vs-implemented status.

The goal is that after every merge to `main`, `/AGENTS.md` accurately represents the current project and does not leave another agent with stale or conflicting instructions.
