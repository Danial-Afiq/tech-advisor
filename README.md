# Tech Advisor

CS203 Human-AI Collaborative Software Development group project.

A personalised AI tech advisor that monitors meaningful technology changes and helps users decide whether an upgrade is actually worth considering based on what they own, what they care about, and their budget.

## Architecture at a glance

```text
React + TypeScript + Vite
        |
        | REST API
        v
Java 21 + Spring Boot
  - Auth / CRUD / scheduling / async work
        |
        +----------------------+
        |                      |
        v                      v
PostgreSQL + pgvector    Python + FastAPI
(rows + embeddings)     (RAG + embeddings)
                               |
                               v
                           LLM API
                        (SMU-X budget)
```

Spring Boot is the main application backend and database owner.

The Python service is planned as a small, stateless AI service that receives one event + one user profile at a time, retrieves relevant context, calls the LLM, validates the result, and returns a structured assessment.

## Tech stack

| Function | Chosen tool |
| --- | --- |
| Frontend | React + TypeScript + Vite |
| Frontend hosting | Vercel |
| Backend API | Java 21 + Spring Boot |
| Build tool | Maven |
| Database | PostgreSQL |
| ORM | Spring Data JPA + Hibernate |
| Schema migrations | Flyway |
| Local database | PostgreSQL 17 with pgvector via Docker Compose |
| Hosted database | Neon PostgreSQL |
| Backend hosting | Fly.io |
| Authentication | Spring Security; USER / ADMIN roles |
| API docs | Swagger / OpenAPI |
| AI service | Python + FastAPI |
| Vector storage | pgvector (PostgreSQL extension) |
| LLM access | API via SMU-X budget |
| CI/CD | GitHub Actions |
| Source control | GitHub; all code via pull requests |
| Project tracking | Jira |

## Repository structure

```text
tech-advisor/
├── backend/                  # Spring Boot application (+ Dockerfile, fly.toml)
├── frontend/                 # React + TypeScript + Vite application
├── ai/                       # Python FastAPI evidence-grading service (+ Dockerfile)
├── docs/                     # Project / technical notes
├── scripts/                  # Local demo helpers
├── .github/
│   └── workflows/
│       ├── ci.yml            # Tests and build checks
│       └── cd.yml            # Backend deployment to Fly.io
├── docker-compose.yml        # Local PostgreSQL + AI service
├── .env                      # ALL config and secrets (git-ignored)
├── .env.example              # Committed template for the above
└── README.md                 # This file
```

**Two Dockerfiles, one application.** A Dockerfile builds exactly one image, and
the backend (JVM) and AI layer (Python) have nothing in common at the image
level, so each owns its own. `docker-compose.yml` is what composes them into a
single running system. The frontend has no Dockerfile because it deploys to
Vercel rather than as a container.

## Runtime flow

When a user accesses the deployed application:

```text
User
 |
 v
Vercel
React frontend
 |
 | HTTPS / REST API
 v
Fly.io
Spring Boot API
 |             |
 |             v
 |        Python AI service
 |             |
 v             v
Neon        LLM API
PostgreSQL
```

Users access the application through the **Vercel frontend URL**.

The React application then communicates with the Spring Boot REST API hosted on Fly.io. Spring Boot owns authentication, business logic, CRUD operations, scheduling and persistence.

Neon hosts the production PostgreSQL database.

## Deployment architecture

```text
Frontend
React + TypeScript + Vite
        |
        v
     Vercel
        |
        | REST API
        v
     Fly.io
Spring Boot backend
        |
        v
      Neon
   PostgreSQL
```

Current / planned hosting:

```text
Frontend  -> Vercel
Backend   -> Fly.io
Database  -> Neon PostgreSQL
AI        -> Python + FastAPI service
LLM       -> external API using SMU-X budget
```

The public backend is hosted at:

```text
https://tech-advisor-backend.fly.dev
```

The production frontend URL will be provided by Vercel once the frontend deployment is configured.

## Frontend configuration

The frontend should not hardcode the backend URL.

Instead, the application reads:

```text
VITE_API_BASE_URL
```

For local development, the backend defaults to:

```text
http://localhost:8080
```

Example:

```env
VITE_API_BASE_URL=http://localhost:8080
```

In Vercel, the production value should point to the hosted Fly.io backend:

```env
VITE_API_BASE_URL=https://tech-advisor-backend.fly.dev
```

`VITE_` environment variables are bundled into the browser application and therefore must **never contain secrets**.

The backend URL is public configuration and is safe to expose.

## CORS

The frontend and backend run on different origins:

```text
Vercel
https://<project>.vercel.app

        -> REST API ->

Fly.io
https://tech-advisor-backend.fly.dev
```

Spring Boot therefore needs to allow requests from the deployed frontend.

The backend uses:

```text
CORS_ALLOWED_ORIGINS
```

Local default:

```text
http://localhost:5173
```

On Fly.io, the variable should be set to the production Vercel URL:

```text
CORS_ALLOWED_ORIGINS=https://<project>.vercel.app
```

Do not hardcode deployment-specific origins into application code when they can be configured through environment variables.

## Backend health monitoring

Spring Boot exposes an Actuator health endpoint:

```text
GET /actuator/health
```

A healthy application should return:

```json
{
  "status": "UP"
}
```

Fly.io uses this endpoint to determine whether the backend is healthy before routing traffic to it.

The Fly health check is configured in:

```text
backend/fly.toml
```

Conceptually:

```text
Fly.io
   |
   | GET /actuator/health
   v
Spring Boot
   |
   +--> application healthy
   |
   +--> database connectivity
```

This gives the deployment platform a more meaningful health signal than simply checking whether port `8080` is open.

## Event pipeline

When new technology data arrives:

```text
Scraper(s) -----------+
                      |
Admin manual entry ---+
                      v
              EventSource interface
              (normalised event shape)
                      |
                      v
            Spring stores the event
              (Flyway-managed DB)
                      |
                      v
                Async fan-out
          (one job per affected user)
                      |
                      v
              AI service assesses
          (retrieve context + call LLM)
                      |
                      v
               Assessment saved
       (model, prompt version, confidence)
                      |
                      v
              In-app notification
          (user accepts or dismisses)
```

The simulator / admin path is useful for reliable demos when live data is insufficient.

## Data ingestion

The ingestion runner implementation and source integration guide are in
[docs/ingestion.md](docs/ingestion.md). It supports an anchored 24-hour schedule,
asynchronous admin-triggered runs, typed source payloads, and persistent execution
history. Local simulated sources demonstrate the pipeline; production admin
activation depends on the account authentication integration. The admin panel is
available at `/admin/ingestion`.

The current architecture defines two main ingestion paths:

1. **Web scraping** — planned for sources such as PCPartPicker, RTINGS and manufacturer specification pages where no suitable free API is available.
2. **Manual admin entry** — admins can add hardware-release events manually, which is also useful for controlled demo scenarios.

Before writing any scraper, check the target site's **robots.txt** and **terms of service**, and use sensible rate limiting.

## AI / ML service

Spring sends the Python service **one event + one user's profile/inventory per request**.

The Python service remains stateless.

The intended flow is:

1. **Receive** — Spring sends the user profile, inventory and event in the request body.
2. **Gather context** — exact spec deltas come from SQL; vector retrieval is for prose such as review verdicts, bottleneck discussion, known issues and "who should actually buy this".
3. **Assemble prompt** — use a prompt template loaded at startup with fixed verdict options and case-specific context.
4. **Call the model** — require structured JSON, not free-form prose.
5. **Validate** — reject malformed responses or verdicts outside the allowed list; retry once, then return `assessment unavailable` rather than inventing a fallback verdict.
6. **Return** — Spring persists the validated assessment.

The Python service should **not** own authentication, scheduling, notifications, or user-selection logic.

Spring handles those responsibilities first, including cheap SQL filtering to decide which users are affected.

### The contract Spring Boot must satisfy

`POST /assess`, with `Authorization: Bearer <AI_SERVICE_TOKEN>`. Request and
response shapes live in `ai/app/schemas.py`, which is authoritative; Swagger
renders them at `/docs`.

Three things the Java side owns before it may call this route:

1. **The preference gate and the verdict.** If the gate fails outright, the
   verdict is `NO_MEANINGFUL_CHANGE` and no call is made.
2. **The maturity gate.** No reviews, reviews too close to release, or fewer
   than `MIN_CHUNKS` passages means `confidence = '-'`, template reasoning, and
   no call. This service cannot make that decision: it never sees
   `products.release_date` against the whole corpus, only the passages it
   retrieved. Two cases the spec's pseudocode does not cover and the Java gate
   must: `release_date IS NULL`, and chunks whose document has no
   `published_at`.
3. **Every number.** `benchmark_uplift_pct` with `higher_is_better` already
   applied, `device_age_months`, `age_days`, `delta_pct`, `vs_budget`, and
   `spec_overrides` already folded into `spec_deltas`. The model does no
   arithmetic and no date maths.

#### Persisting the response

| Response field | Column |
| --- | --- |
| `evidence_grade` | `recommendations.confidence` |
| `evidence_findings` | `factor_analysis` → `evidence` |
| `summary` | `reasoning` |
| `meta.ai_model` | `ai_model` |
| `meta.prompt_version` | `prompt_version` |
| `meta.retrieved_chunk_ids` | `input_snapshot.retrieved_chunk_ids` |
| `meta.retrieval` | `input_snapshot.retrieval` |
| `request_id` | `input_snapshot.request_id` |
| `system_log` (when present) | one `system_log` row |

Keep `factor_analysis.deterministic` and `factor_analysis.evidence` as separate
keys. They may disagree — a spec-strong phone with widespread battery
complaints should read `WORTH_CONSIDERING` with grade `D` — and that
disagreement is the most useful thing the system can show. Never merge them.

`supporting_chunk_ids` on each finding is what Spring persists. The `P1`-style
refs also come back, but they are local to one request and mean nothing once it
ends; they are returned for debugging only.

### Decisions taken in the AI layer

- **Refs are resolved before responding.** Each finding also carries
  `supporting_chunk_ids`, and `meta.retrieved_chunk_ids` holds all K in rank
  order — otherwise persisted refs could never be resolved back to a chunk.
- **The service returns the degraded result itself.** On a model failure, a
  refusal, or validation failing twice, it returns HTTP 200 with
  `evidence_grade: "-"`, `meta.degraded: true`, a `degraded_reason`, and a
  ready-formed `system_log` row. Spring's path is then identical whether the
  call worked or not. It never fabricates a grade or a summary.
- **A mostly-irrelevant retrieval produces `-`, not a thin grade.** If the model
  marks more than `IRRELEVANT_REF_LIMIT` (default 0.75) of retrieved passages as
  describing a different product, the grade is discarded. Without this a grade
  can rest on a single stray passage. Set it to `1.0` to disable.
- **At least one finding is required** alongside a real grade.
- **`source_type` without a schema change.** Approximated by a `source_name` →
  type allowlist in `ai/app/config.py`, stamped into each passage header so the
  prompt can tell an owner report from a launch-day editorial.
- **`source_name` is sanitised.** It is scraped text sitting in a structured
  header; `|`, `[`, `]` and newlines are stripped so a source name cannot forge
  a passage header.

### The stand-in vector store

pgvector is not enabled and `review_chunks` does not exist, so retrieval runs
against `LocalVectorStore` — JSON files under `ai/data/vector_store/`.

`chunks.json` is committed there as **demo data**: five passages for product
`812` (plus one for `999`, which must never be retrieved — it is what proves
the `product_id` filter works). That is enough to exercise a real end-to-end
`/assess` against a live model. Delete it to see the un-ingested behaviour: an
empty store is a legitimate state, not a misconfiguration, and the service
answers `-` for it.

The path is resolved against the `ai` package root, never the working
directory — `/srv` in the container, which is where compose mounts the store.
A CWD-relative path failed silently and expensively: started from the repo
root it found no directory, retrieved nothing, and degraded every assessment
to `-` with `NO_PASSAGES_RETRIEVED` and nothing in the logs explaining why.

To add more, drop another `*.json` file in that directory shaped the same way:

```json
[
  {
    "chunk_id": 4412,
    "product_id": 812,
    "source_name": "Reddit r/GalaxyS25",
    "published_at": "2025-11-02",
    "chunk_text": "Battery life has been noticeably worse since the update..."
  }
]
```

`published_at` may be null. `embedding` is optional and is computed on load when
absent, so fixtures can be plain text.

`DeterministicEmbedder` is a hashed bag-of-words, not a semantic model. It ranks
by shared vocabulary and understands nothing. **It must be replaced at the same
moment the real store is wired in** — a chunk embedded by one model and a query
embedded by another produce plausible nonsense rather than an error.

Switching over: enable the extension, create `review_chunks`, pick an embedding
model, set its dimension on the column, swap the embedder, set
`VECTOR_STORE=pgvector`. `PgVectorStore` and `SEARCH_SQL` already exist in
`ai/app/retrieval/store.py` so the two paths cannot drift; the SQL keeps the
mandatory `product_id` filter, without which passages about other phones get
graded as if they described the candidate.

### Model and provider configuration

The layer is **vendor-neutral**. `Assessor` depends only on the `Llm` protocol
in `ai/app/llm.py`, so which company answers is a config change, not a code
change. `LLM_PROVIDER` selects one of two adapters:

| `LLM_PROVIDER` | Adapter | Reaches |
| --- | --- | --- |
| `anthropic` *(default)* | `AnthropicLlm`, native Claude API | Claude models |
| `openrouter` | `OpenAICompatibleLlm` | one key fronting Anthropic, OpenAI, Google, Meta, Mistral, DeepSeek … |
| `openai` | `OpenAICompatibleLlm` | OpenAI directly |
| `custom` + `LLM_BASE_URL` | `OpenAICompatibleLlm` | Ollama, vLLM, Groq, Together, DeepSeek, a company gateway |

Only two adapters are needed for all of that, because the OpenAI
chat-completions format is a de-facto standard — the base URL is the only thing
that differs between those vendors.

`LLM_MODEL` travels with `LLM_PROVIDER` and both live in the root `.env`. The
same model is named differently per vendor (`claude-opus-5` natively,
`anthropic/claude-opus-5` through OpenRouter), so changing one without the
other is always a mistake.

#### Structured output is tiered

Not every model reachable through a gateway can enforce a JSON schema
server-side, so `LLM_SCHEMA_MODE=auto` (the default) starts at the strongest
tier and steps down only when a provider rejects the request *because of the
response format*, then remembers what worked so the wasted request is paid once
per process rather than per assessment:

```text
json_schema  ->  json_object  ->  a schema spelled out in the prompt
```

Pin it (`json_schema`, `json_object`, `prompt`) to skip the discovery. A 400
for any other reason — a bad model name, say — is surfaced rather than
mistaken for a format problem and quietly retried at a weaker tier.

This is where the existing design pays off: whatever a weaker model still gets
wrong is caught by `validate()` and the retry/degrade path, so the result is a
degraded `-` rather than a fabricated grade.

#### What each adapter sends

Native Anthropic (`claude-opus-5`, via `client.beta.messages.create`):

- **No `temperature`.** Sampling parameters are rejected with a 400 on this
  model. Depth is controlled with `LLM_EFFORT` (`low`…`max`, default `medium`).
- Thinking is on by default and is billed inside `max_tokens`, which is why
  `LLM_MAX_TOKENS` is generous relative to how small the JSON output is.
- `output_config.format` constrains the response to the schema, so the field
  order in `output_schema()` is enforced, not merely requested. That order is
  load-bearing: models generate left to right, so the grade is committed before
  any personalised prose is written. **Do not reorder it.**
- `fallbacks="default"` re-runs a request server-side if a safety classifier
  declines it. Disable with `LLM_FALLBACKS_ENABLED=false`.
- Prompt caching on the system block.

OpenAI-compatible:

- `response_format` per the tier above; the system prompt is left clean when
  the provider enforces the schema, so the cached prefix is not wasted on
  instructions the API already guarantees.
- Reasoning depth is **opt-in** (`LLM_SEND_EFFORT=true`), because many models
  behind a gateway reject the parameter outright.
- Truncation (`finish_reason: "length"`) is reported as itself rather than
  left to fail validation, since no retry can fix a token ceiling.
- Gateways sometimes report upstream failures as HTTP 200 with no choices;
  that is caught explicitly.
- `meta.ai_model` records the model that **actually answered**, which is not
  always the one requested — gateways route and substitute.

The Anthropic-specific levers are kept rather than flattened to a lowest common
denominator, so running on Claude does not cost you effort control, refusal
fallbacks or prompt caching just because the layer also supports other vendors.

Full validation still runs even though the schema is enforced API-side: a
hallucinated ref is indistinguishable from a real one to any schema validator,
and it is the failure that would otherwise attribute a grade to evidence that
was never retrieved.

### Not built in the AI layer

Rate limiting and a per-event call cap. One `market_events` row fans out to
every affected `user_device`, and each pair clearing the maturity gate fires a
call; at demo scale one price drop can be dozens of calls in a burst against a
shared budget. The bound belongs in the Java orchestrator, which is what knows
the fan-out. `LLM_TIMEOUT_SECONDS` covers only the single call.

## Personalisation

Personalisation is the project's primary advanced AI capability.

The recommendation should consider information such as:

```text
user profile
+
owned devices
+
preferences / priorities
+
budget
+
incoming technology event
=
personalised upgrade assessment
```

Two users receiving information about the same product may therefore receive different recommendations depending on their existing hardware and priorities.

RAG may be added later to improve contextual evidence retrieval, but the core personalisation flow should work independently of RAG.

## RAG: what goes into vector search?

Use SQL for exact structured facts such as:

- specifications,
- prices,
- benchmark values,
- known numeric deltas.

Use pgvector for relevant unstructured prose such as:

- review verdicts,
- bottleneck discussion,
- known issues,
- buyer-fit / "who should buy this" commentary.

Filter by category using SQL first, then rank the remaining text by semantic similarity.

A few strong passages are better than sending large amounts of weak context to the model.

## Recommendation outcomes

The planned labels are:

```text
No meaningful change
Worth watching
Worth considering
Strong upgrade candidate
```

Assessments should also include evidence and confidence so users can understand why a recommendation was generated.

The AI-generated assessment itself should remain immutable.

Users may instead:

```text
accept
dismiss
mark irrelevant
```

This feedback can be logged and later used to improve personalisation.

## Security: prompt injection

Scraped web text is untrusted input.

A page could contain text such as an instruction attempting to manipulate the model rather than provide technology information.

Planned mitigations include:

- clearly delimit retrieved content and label it as untrusted data,
- restate the task after the retrieved block,
- validate model output against the fixed verdict enum / JSON schema,
- prefer moderated / trusted sources where possible,
- avoid allowing retrieved content to redefine system instructions.

## Local development

### Requirements

Developers should have:

```text
Git
Docker Desktop
Java 21
Node.js
```

A separate Maven installation is not required because the backend includes the Maven Wrapper.

### Clone the repository

```bash
git clone https://github.com/Danial-Afiq/tech-advisor.git
cd tech-advisor
```

### Local PostgreSQL

Everyone runs their **own local database**.

Docker Compose keeps the PostgreSQL setup consistent across machines.

Create the local environment file:

PowerShell:

```powershell
Copy-Item .env.example .env
```

macOS / Linux:

```bash
cp .env.example .env
```

Start PostgreSQL:

```bash
docker compose up -d
```

Check it:

```bash
docker compose ps
```

Stop it:

```bash
docker compose down
```

If local development data can be discarded and the database needs a clean reset:

```bash
docker compose down -v
docker compose up -d
```

### Run the backend

From:

```text
backend/
```

Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

macOS / Linux:

```bash
./mvnw spring-boot:run
```

The backend runs locally at:

```text
http://localhost:8080
```

Health endpoint:

```text
http://localhost:8080/actuator/health
```

### Run the frontend

From:

```text
frontend/
```

Install dependencies:

```bash
npm install
```

Start the Vite development server:

```bash
npm run dev
```

The frontend normally runs at:

```text
http://localhost:5173
```

Build the frontend:

```bash
npm run build
```

Frontend environment variables (`VITE_API_BASE_URL`, `VITE_INGESTION_DEMO`) are
read from the **repo-root** `.env`, not from `frontend/`. See
[Configuration and secrets](#configuration-and-secrets).

### Run the AI layer

From:

```text
ai/
```

Create the virtual environment and install, including dev dependencies:

```bash
python -m venv .venv
./.venv/Scripts/python.exe -m pip install -e ".[dev]"     # Windows
# source .venv/bin/activate && pip install -e ".[dev]"    # macOS / Linux
```

There is no `ai/.env`. Configuration comes from the repo-root `.env`, resolved
from the source file rather than the working directory, so the server behaves
identically started from `ai/` or from the repo root:

```bash
./.venv/Scripts/python.exe -m uvicorn app.main:app --reload --port 8000
```

Swagger UI is at `http://localhost:8000/docs`, health at `/health`.

```bash
./.venv/Scripts/python.exe -m pytest        # 90 tests, no live model calls
```

**What works without any API key.** The test suite, `/health`, `/docs` and the
whole container build need no credential — the tests inject a fake model client
at two levels. `POST /assess` is the *only* thing that makes a live call, and it
will fail without a key for whichever `LLM_PROVIDER` is selected. An empty key
is a legitimate local state; it simply means the grading path is untested on
your machine.

**Switching provider** is two lines in the root `.env`:

```bash
LLM_PROVIDER=openrouter
LLM_MODEL=anthropic/claude-opus-5     # or openai/gpt-5, google/gemini-..., meta-llama/...
OPENROUTER_API_KEY=sk-or-...
```

Nothing else changes — `Assessor` depends on the `Llm` protocol, not on a
vendor. To run against a local model with no key and no cost at all:

```bash
LLM_PROVIDER=custom
LLM_BASE_URL=http://localhost:11434/v1    # Ollama
LLM_MODEL=qwen2.5
LLM_API_KEY=ollama                        # any non-empty string; Ollama ignores it
```

Or run it in Docker alongside PostgreSQL, which reads the same root `.env`:

```bash
docker compose up -d postgres ai
curl http://localhost:8000/health
```

## Database schema changes

Schema changes are managed through **Flyway migrations**.

This ensures that local development, CI and the hosted Neon database apply schema changes in a consistent order.

Migration files should live under:

```text
backend/src/main/resources/db/migration/
```

Example:

```text
V1__create_users_table.sql
V2__create_products_table.sql
V3__add_user_preferences.sql
```

Rules:

- schema changes should be committed as migration files,
- do not manually modify only your own database,
- do not edit an already-applied shared migration,
- create a new migration for the next schema change,
- review migration files through pull requests.

Conceptually:

```text
Migration committed
       |
       v
Local PostgreSQL
       |
       v
CI PostgreSQL
       |
       v
PR merged
       |
       v
Fly.io backend starts
       |
       v
Flyway checks Neon
       |
       v
Missing migration applied
```

Neon therefore does not require a separate application deployment workflow.

Flyway manages database schema changes when the deployed Spring Boot application starts.

Flyway's `V4__enable_pgvector.sql` enables the `vector` extension on startup. Local Docker Compose and CI use the matching pgvector-enabled PostgreSQL 17 image; the hosted Neon database must also support the extension.

## Git workflow

Do not develop directly on `main`.

```text
feature / fix / chore / docs branch
              |
              v
          Pull Request
              |
              v
           CI checks
              |
              v
          Code review
              |
              v
             Merge
```

Example branch names:

```text
feature/user-profile
feature/event-ingestion
fix/login-validation
chore/deployment-hardening
docs/update-architecture-readme
```

All project code contributions should go through pull requests.

## Continuous Integration

GitHub Actions CI runs:

```text
Pull Request -> main
```

and:

```text
push / merge -> main
```

The current CI pipeline performs:

```text
Backend - Test and Build
Frontend - Test and Build
Repository Checks
```

### Backend CI

The backend job:

- starts a temporary PostgreSQL service,
- configures Spring Boot to use that database,
- runs Maven tests,
- builds the backend application.

The CI PostgreSQL database is temporary and is destroyed after the workflow completes.

It does **not** use the hosted Neon production database.

### Frontend CI

The frontend job:

- installs Node.js,
- installs frontend dependencies,
- runs available tests,
- builds the React/Vite application.

### Repository checks

Repository checks verify that important setup files exist and help prevent accidental commits of sensitive local configuration such as `.env`.

## Continuous Deployment

Backend deployment is handled through a separate GitHub Actions CD workflow.

The deployment flow is:

```text
Developer opens PR
        |
        v
       CI
        |
        v
Human review / approval
        |
        v
Merge into main
        |
        v
CI runs on main
        |
        | success
        v
       CD
        |
        v
flyctl deploy
        |
        v
     Fly.io
        |
        v
Spring Boot backend
        |
        v
Neon PostgreSQL
```

CD only deploys if the CI workflow on `main` completes successfully.

Therefore:

```text
CI success   -> deployment runs
CI failure   -> deployment skipped
CI cancelled -> deployment skipped
```

The CD workflow checks out the exact commit that passed CI before deploying it.

## Frontend deployment

The React/Vite frontend is intended to be hosted on **Vercel**.

Vercel connects directly to the GitHub repository and uses:

```text
Root Directory: frontend
```

Production configuration:

```text
Framework: Vite
Build Command: npm run build
Output Directory: dist
```

Vercel can automatically build and deploy the frontend when changes are merged into the configured production branch.

This means frontend deployment does not require a separate custom `flyctl`-style GitHub Actions job.

## CI/CD overview

```text
                         GitHub
                            |
                           PR
                            |
                            v
                    GitHub Actions CI
                   /        |        \
                  /         |         \
           Backend       Frontend     Repo
          Test/Build    Test/Build    Checks
                  \         |         /
                   \        |        /
                    +-------+-------+
                            |
                         approval
                            |
                            v
                           main
                            |
                    +-------+-------+
                    |               |
                    v               v
             GitHub Actions       Vercel
                   CD              Build
                    |               |
                    v               v
                 Fly.io        React frontend
                    |
                    v
              Spring Boot
                    |
                    v
                  Neon
              PostgreSQL
```

## Configuration and secrets

**One file.** The repo-root `.env` is the single source of truth for every
secret and every setting that more than one service needs. There is no
`ai/.env` and no `frontend/.env`.

Copy `.env.example` to `.env` and fill in the blanks. `.env` is git-ignored and
CI fails if it is ever committed; `.env.example` is committed and must keep the
same key set.

How each service reaches it:

| Service | Mechanism |
| --- | --- |
| backend | `application.properties` placeholders, read from the process environment |
| ai | pydantic-settings, path resolved from `ai/app/config.py` — **not** the working directory |
| frontend | Vite `envDir: '..'` in `frontend/vite.config.ts` |
| compose | interpolated from the root `.env`, listed per service so each container sees only the variables it needs |

Service tuning that is **not** a secret stays in code. The AI layer's knobs
(`K`, `LLM_MODEL`, `LLM_EFFORT`, `CHUNK_CHAR_CAP`, `MAX_RETRIES`,
`IRRELEVANT_REF_LIMIT`, …) are typed defaults in `ai/app/config.py`, still
overridable from the environment when you actually need to change one.

### The variables

| Variable | Used by | Local default | Production value lives in |
| --- | --- | --- | --- |
| `POSTGRES_DB` | backend, compose | `techadvisor` | Fly secret (as `SPRING_DATASOURCE_URL`) |
| `POSTGRES_USER` | backend, compose | `techadvisor` | Fly secret (`SPRING_DATASOURCE_USERNAME`) |
| `POSTGRES_PASSWORD` | backend, compose | `devpassword` | Fly secret (`SPRING_DATASOURCE_PASSWORD`) |
| `DB_HOST` | backend | `localhost` | — (Fly uses the datasource URL) |
| `DB_PORT` | backend, compose | `5433` | — |
| `LLM_PROVIDER` | ai | `anthropic` | not deployed yet |
| `LLM_MODEL` | ai | `claude-opus-5` | not deployed yet |
| `ANTHROPIC_API_KEY` | ai | *(blank)* | not deployed yet |
| `OPENROUTER_API_KEY` | ai | *(blank)* | not deployed yet |
| `OPENAI_API_KEY` | ai | *(blank)* | not deployed yet |
| `LLM_BASE_URL` | ai (`custom` provider) | *(blank)* | not deployed yet |
| `LLM_API_KEY` | ai (`custom` provider) | *(blank)* | not deployed yet |
| `AI_SERVICE_TOKEN` | ai, backend | *(blank)* | not deployed yet |
| `AI_PORT` | compose | `8000` | — |
| `VITE_API_BASE_URL` | frontend | `http://localhost:8080` | Vercel environment variable |
| `VITE_INGESTION_DEMO` | frontend | `false` | Vercel environment variable |
| `INGESTION_SCHEDULING_ENABLED` | backend | `false` | Fly secret |
| `INGESTION_ANCHOR` | backend | `2026-09-17T05:00:00Z` | Fly secret |
| `INGESTION_ENABLED_SOURCES` | backend | *(blank)* | Fly secret |
| `INGESTION_DEMO_PASSWORD` | backend (`ingestion-demo` profile) | unset — set in your shell | — local demo only |
| `CORS_ALLOWED_ORIGINS` | backend | `http://localhost:5173` (from `application.properties`) | Fly secret |
| `JWT_SECRET` | *reserved* | *(blank)* | — no code reads it yet |

### Three things that will bite you silently

1. **A `frontend/.env` is not read.** `envDir: '..'` points Vite at the repo
   root, so a `.env` created inside `frontend/` is ignored with no error — the
   app just falls back to `http://localhost:8080`. Frontend values go in the
   root `.env`. (`frontend/.env.example` remains as documentation of which
   variables exist; example files are never loaded by Vite either way.)
2. **A blank `AI_SERVICE_TOKEN` disables authentication on `/assess`.** That is
   deliberate for local development — see `require_token` in `ai/app/main.py` —
   but it must be set anywhere the service is reachable from outside.
3. **A blank credential is a valid local state.** Tests, `/health`, `/docs` and
   the container build all work without one. Only `POST /assess` needs a key,
   and it fails loudly rather than degrading quietly. Only the key matching
   `LLM_PROVIDER` is read — the others may stay blank.

### Local vs Fly: two shapes for the database

Local and CI compose the connection from `DB_HOST` / `DB_PORT` / `POSTGRES_*`.
Fly instead sets `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and
`SPRING_DATASOURCE_PASSWORD` directly, which override the composed values. This
is a known inconsistency, left as-is deliberately so that consolidating local
config cannot disturb the running deployment.

### GitHub Actions secrets

GitHub Actions uses:

```text
FLY_API_TOKEN
```

to authenticate automated backend deployments.

The actual token value must never appear in:

```text
README.md
.env.example
fly.toml
GitHub Actions YAML
source code
Git history
```

Only the reference to the secret is stored in the workflow.

## Secret ownership

```text
All local development config and secrets
-> the repo-root .env (one file, every service)

GitHub -> Fly deployment credential
-> GitHub Actions secret

Fly backend runtime secrets
-> Fly.io secrets

Frontend public build configuration
-> Vercel environment variables
```

This keeps credentials in the environment where they are actually needed, and
keeps exactly one of them on a developer's machine.

## Testing strategy

Developers should add meaningful tests alongside important functionality rather than chasing arbitrary test counts.

High-value areas include:

- authentication and RBAC,
- CRUD validation,
- event processing,
- user personalisation,
- recommendation validation,
- malformed AI output handling,
- database migrations,
- important frontend flows.

Paid external AI calls should be mocked or replaced with deterministic test data in CI where possible.

### AI layer tests

`cd ai && ./.venv/Scripts/python.exe -m pytest` — 90 tests, **no live model
calls**, no API key, no cost. They stub the model at two levels:

- `ai/tests/conftest.py` replaces the whole `Llm` implementation, for testing
  the assessment flow: valid responses and ref mapping, hallucinated refs,
  invalid grades, factors outside the closed list, the retry firing once and
  only once, the `product_id` filter, delimiter stripping, the degraded paths
  and the HTTP surface.
- `ai/tests/test_providers.py` stubs one layer lower, at the wire format, so
  the request actually sent to each vendor is asserted rather than reviewed by
  eye: provider selection, the schema/json_object/prompt tiers and the
  step-down between them, refusals, truncation, gateway errors, and the
  Anthropic request shape (cache breakpoint, effort, fallback betas, no
  sampling parameters).

Plus configuration resolution from the root `.env`.

Two honest limits worth recording.

**No test proves a real provider accepts these requests.** The stubs assert
what is sent, not that any vendor is happy to receive it. A wrong beta header
or an unsupported parameter is a 400 in production that every test still
passes through. One live call settles it.

 The prompt-injection test proves that an
injected passage reaches the model only as data inside the delimiters, that a
passage cannot close the block early, and that the grade matches a control run
without the poisoned chunk. It **cannot** prove the model itself ignores the
injected instruction, because the model is mocked. Confirming that needs one
live call and is a manual pre-demo check.

Note that the AI layer currently has **no CI job** — these tests do not run on
push. Wiring one up is outstanding work.

## Deployment principles

The project intentionally keeps infrastructure relatively simple.

Current deployment stack:

```text
React/Vite   -> Vercel
Spring Boot  -> Fly.io
PostgreSQL   -> Neon
CI/CD        -> GitHub Actions
```

Additional infrastructure should only be introduced when it solves a real project requirement.

The project does not currently require infrastructure such as:

```text
Kubernetes
Jenkins
Kafka
service meshes
multiple production clusters
complex infrastructure-as-code
```

The priority is a reliable end-to-end product rather than unnecessary infrastructure complexity.
