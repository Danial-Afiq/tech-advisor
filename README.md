# Tech Advisor

CS203 Human-AI Collaborative Software Development group project.

A personalised AI tech advisor that monitors meaningful technology changes and helps users decide whether an upgrade is actually worth considering based on what they own, what they care about, and their budget.

## Architecture

```text
React + TypeScript frontend
          |
          | REST/JSON
          v
Java 21 + Spring Boot backend
  - Spring Web
  - Spring Security
  - Spring Data JPA / Hibernate
  - Swagger / OpenAPI
          |
          +--> External AI API
          +--> Product / news data sources
          |
          v
PostgreSQL
```

Planned external inputs include Open Icecat, NVIDIA/AMD RSS, curated benchmark data, and a simulated event feed for reliable testing/demo scenarios.

## Tech stack

| Area | Choice |
| --- | --- |
| Frontend | React + TypeScript + npm |
| Backend | Java 21 + Spring Boot |
| Build tool | Maven |
| Database | PostgreSQL |
| ORM | Spring Data JPA + Hibernate |
| Local database | PostgreSQL via Docker Compose |
| Hosted database | Neon PostgreSQL (planned) |
| Authentication | Spring Security; USER / ADMIN roles |
| API docs | Swagger / OpenAPI |
| CI | GitHub Actions |
| Source control | GitHub; all code via pull requests |
| Project tracking | Jira |

We are intentionally keeping the stack small. We are **not** adding Jenkins, Kubernetes, Kafka, Redis, RabbitMQ, microservices, a separate AI service, or Flyway unless a real need appears.

## Repository structure

```text
tech-advisor/
├── backend/              # Spring Boot project
├── frontend/             # React project
├── docs/                 # Project/technical notes
├── .github/workflows/    # CI
├── docker-compose.yml    # Local PostgreSQL
├── .env.example          # Example local config (no secrets)
└── README.md
```

The backend and frontend folders are placeholders until those projects are initialised.

## Local PostgreSQL

Everyone runs their **own local PostgreSQL database**, but Docker Compose keeps the PostgreSQL version and basic configuration consistent.

1. Install Docker Desktop.
2. Copy the example config:

```bash
cp .env.example .env
```

3. Start PostgreSQL:

```bash
docker compose up -d
```

4. Stop PostgreSQL:

```bash
docker compose down
```

5. If your local development database becomes messy and you are okay losing local data:

```bash
docker compose down -v
docker compose up -d
```

### Database schema rule

For the initial prototype, **JPA entities are the source of truth for the schema** and Hibernate will use `ddl-auto=update` during local development.

**Do not manually add, rename, or delete tables/columns in pgAdmin or SQL.** Make schema changes through the Java entity classes, commit them through a PR, and let teammates pull the same code. If schema changes become too complex later, we can introduce Flyway migrations then.

## Git workflow

Do not develop directly on `main`.

```text
main
  └── feature/... / fix/... / chore/...
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

Suggested branch names:

```text
feature/user-profile
feature/device-management
fix/login-validation
chore/update-ci
```

All project code contributions should go through pull requests.

## CI

GitHub Actions runs on pull requests to `main` and pushes to `main`.

For now it:

- checks the repository structure,
- runs Maven tests/build automatically once `backend/pom.xml` exists,
- runs npm install/tests/build automatically once the frontend is initialised.

The workflow intentionally skips backend/frontend build steps until those projects actually exist, so we do not add fake application code just to make CI green.

When features are implemented, developers should add a few **meaningful tests for important behaviour** rather than trying to test everything. Examples: registration succeeds/fails correctly, USER cannot access ADMIN endpoints, an obvious upgrade scenario produces the expected classification, or malformed AI output is rejected.

## Configuration and secrets

Never commit real secrets or `.env` files.

Typical variables will include:

```text
POSTGRES_DB
POSTGRES_USER
POSTGRES_PASSWORD
DB_HOST
DB_PORT
AI_API_KEY
JWT_SECRET
```

Use `.env` locally and hosted environment variables/secrets in production. `.env.example` contains safe placeholders only.

## Planned backend responsibilities

The Spring Boot backend will own:

- REST APIs and Swagger documentation,
- users, profiles and preferences,
- authentication and USER/ADMIN authorization,
- owned devices,
- products/specifications,
- technology events such as launches and price changes,
- data ingestion from live/simulated sources,
- recommendation generation and validation,
- persistence of recommendations, confidence/evidence and feedback.

The AI API is **one component in the pipeline**, not a chatbot proxy. The backend should combine changing evidence, structured facts and user context before asking the model for a structured personalised assessment.

## Planned recommendation pipeline

```text
Tech change detected
        |
        v
Collect relevant product/evidence data
        |
        v
Load user's devices + preferences
        |
        v
Apply deterministic facts/checks
        |
        v
AI personalised impact assessment
        |
        v
Validate structured output
        |
        v
Store recommendation + evidence/confidence
        |
        v
Show/notify user
```

RAG may be added later for retrieving relevant evidence from changing unstructured sources such as launch/news documents. It is not required for the initial backend skeleton.

## Deployment (later)

Current plan:

```text
Frontend -> hosted web frontend
Backend  -> public cloud service
Database -> Neon PostgreSQL
```

We will get the app working locally first, then deploy the Spring Boot backend and database, then automate deployment only if it is useful. Full CD is not needed at the start.
