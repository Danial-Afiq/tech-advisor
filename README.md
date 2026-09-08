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
| Local database | PostgreSQL via Docker Compose; pgvector-enabled image planned |
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
├── backend/                  # Spring Boot application
├── frontend/                 # React + TypeScript + Vite application
├── docs/                     # Project / technical notes
├── .github/
│   └── workflows/
│       ├── ci.yml            # Tests and build checks
│       └── cd.yml            # Backend deployment to Fly.io
├── docker-compose.yml        # Local PostgreSQL
├── .env.example              # Safe backend/local config example
└── README.md
```

The Python AI service can be added as its own folder/service when implementation starts.

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

> Note: the current Docker Compose setup uses standard PostgreSQL. Before RAG / vector-storage work starts, switch to the agreed pgvector-enabled PostgreSQL setup and enable the extension.

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

Never commit real secrets or `.env` files.

### Local/backend variables

Common local database variables include:

```text
POSTGRES_DB
POSTGRES_USER
POSTGRES_PASSWORD
DB_HOST
DB_PORT
```

### Frontend variables

```text
VITE_API_BASE_URL
```

Example local value:

```text
http://localhost:8080
```

Production Vercel value:

```text
https://tech-advisor-backend.fly.dev
```

### Backend deployment variables

```text
CORS_ALLOWED_ORIGINS
```

The Fly.io production value should contain the deployed Vercel frontend origin.

### Fly.io runtime secrets

The hosted backend uses Fly.io-managed secrets for database connectivity, including:

```text
SPRING_DATASOURCE_URL
SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_PASSWORD
```

Future application secrets may include:

```text
LLM_API_KEY
JWT_SECRET
```

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
Local development secrets
-> local .env

GitHub -> Fly deployment credential
-> GitHub Actions secret

Fly backend runtime secrets
-> Fly.io secrets

Frontend public build configuration
-> Vercel environment variables
```

This keeps credentials in the environment where they are actually needed.

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