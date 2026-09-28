"""Runtime configuration. Section 11 of the LLM layer specification.

Every value is settable from the environment so it can be tuned during the
demo without a redeploy. Nothing here is hardcoded at a call site.

Secrets and anything a second service also needs live in the repo-root `.env`,
which is the single source of truth for the whole project. The tuning knobs
below keep their defaults here rather than bloating that file; set them in the
environment only when you actually want to change one.
"""

from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

# Resolved from this file, not the working directory, so `uvicorn app.main:app`
# behaves the same run from `ai/` or from the repo root.
REPO_ROOT = Path(__file__).resolve().parents[2]
ENV_FILE = REPO_ROOT / ".env"

# The `ai` package root: `ai/` in a checkout, `/srv` in the container, which is
# also what docker-compose mounts the vector store into. Relative data paths
# resolve against this for the same reason ENV_FILE does - see
# Settings.resolved_vector_store_path.
AI_ROOT = Path(__file__).resolve().parents[1]


class Settings(BaseSettings):
    # `extra="ignore"` is load-bearing: the root `.env` also carries the
    # backend's and frontend's variables, which are not fields here.
    model_config = SettingsConfigDict(env_file=ENV_FILE, extra="ignore")

    # Service
    ai_service_token: str = ""

    # --- Provider selection -------------------------------------------------
    # Which adapter in app/llm.py handles the call: anthropic | openrouter |
    # openai | custom. Everything but `anthropic` goes through the shared
    # OpenAI-compatible adapter, so adding a vendor is a base URL, not code.
    llm_provider: str = "anthropic"

    # Anthropic credential. Blank falls back to the SDK's own resolution
    # (a real ANTHROPIC_API_KEY in the environment, then an `ant auth login`
    # profile) - see AnthropicLlm.__init__.
    anthropic_api_key: str = ""

    # One key fronting many vendors. Used when LLM_PROVIDER=openrouter.
    openrouter_api_key: str = ""

    # Used when LLM_PROVIDER=openai.
    openai_api_key: str = ""

    # Used when LLM_PROVIDER=custom, together with llm_base_url: Ollama,
    # vLLM, Groq, Together, DeepSeek, a company gateway.
    llm_api_key: str = ""
    llm_base_url: str = ""

    # How the JSON contract is enforced. `auto` starts at json_schema and
    # steps down to json_object then prompt only when the provider rejects
    # the format, remembering what worked. Pin it to skip the discovery.
    llm_schema_mode: str = "auto"

    # Send reasoning depth to OpenAI-compatible providers. Off by default:
    # many models behind a gateway reject the parameter outright. Ignored by
    # the Anthropic adapter, which always sends effort.
    llm_send_effort: bool = False

    # Section 11
    k: int = 12
    chunk_char_cap: int = 800
    prompt_version: str = "v1"
    max_retries: int = 1

    # Model. `temperature` is deliberately absent: it is rejected with a 400
    # on Claude Opus 5. Depth is controlled with effort instead.
    llm_model: str = "claude-opus-5"
    llm_max_tokens: int = 16000
    llm_effort: str = "medium"
    llm_timeout_seconds: float = 120.0
    llm_fallbacks_enabled: bool = True

    # --- Retrieval ------------------------------------------------------------
    # "local" reads JSON files from disk; "pgvector" reads review_chunks.
    vector_store: str = "local"
    vector_store_path: str = "data/vector_store"

    # Which embedder produces query vectors. This MUST match whatever embedded
    # the stored chunks - nothing downstream can detect a mismatch, it just
    # returns confident nonsense. Ingestion stamps the name into
    # review_chunks.embedder and startup verifies it.
    embedder: str = "model2vec"
    embedding_dim: int = 512
    # Load the weights from this directory instead of the HuggingFace cache.
    # The Docker image sets it; on a laptop it stays blank and the model is
    # fetched and cached normally.
    embedding_model_path: str = ""

    # --- Database (pgvector only) ---------------------------------------------
    # Field names match the root .env, which the backend and compose already
    # use. Reading review_chunks means this service now holds database
    # credentials, which it previously did not.
    db_host: str = "localhost"
    db_port: int = 5433
    postgres_db: str = "techadvisor"
    postgres_user: str = "techadvisor"
    postgres_password: str = "devpassword"
    # Set to override the five fields above with a single libpq URL.
    database_url: str = ""

    # Guard rail described in the README: if the model declares more than this
    # fraction of the retrieved passages irrelevant, there was not enough
    # on-topic evidence to grade and we return '-' rather than a thin letter.
    irrelevant_ref_limit: float = 0.75

    # Maps a scraped `review_documents.source_name` onto a source type so the
    # passage header can distinguish an owner report from a launch editorial.
    # Stands in for the optional `source_type` column (spec section 12).
    source_types: dict[str, str] = {
        "google shopping reviews via searchapi": "USER_REVIEW",
        "reddit": "FORUM",
        "xda": "FORUM",
        "gsmarena": "PROFESSIONAL_REVIEW",
        "the verge": "PROFESSIONAL_REVIEW",
        "amazon": "USER_REVIEW",
        "trustpilot": "USER_REVIEW",
    }

    def source_type_for(self, source_name: str) -> str:
        lowered = (source_name or "").lower()
        for needle, source_type in self.source_types.items():
            if needle in lowered:
                return source_type
        return "UNKNOWN"


    @property
    def dsn(self) -> str:
        """libpq connection string for the pgvector store."""
        if self.database_url:
            return self.database_url
        return (
            "host=%s port=%d dbname=%s user=%s password=%s"
            % (
                self.db_host,
                self.db_port,
                self.postgres_db,
                self.postgres_user,
                self.postgres_password,
            )
        )

    @property
    def resolved_vector_store_path(self) -> Path:
        """Where the stand-in store actually lives.

        A relative `vector_store_path` resolves against the `ai` package root,
        never the working directory. The CWD-relative version failed silently
        and expensively: started from the repo root it found no directory, so
        retrieval returned nothing, and every assessment degraded to '-' with
        `NO_PASSAGES_RETRIEVED` and no error anywhere to explain why. An
        absolute path is honoured as given.
        """
        path = Path(self.vector_store_path)
        return path if path.is_absolute() else AI_ROOT / path


@lru_cache
def get_settings() -> Settings:
    return Settings()
