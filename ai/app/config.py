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


class Settings(BaseSettings):
    # `extra="ignore"` is load-bearing: the root `.env` also carries the
    # backend's and frontend's variables, which are not fields here.
    model_config = SettingsConfigDict(env_file=ENV_FILE, extra="ignore")

    # Service
    ai_service_token: str = ""

    # Anthropic credential. Blank falls back to the SDK's own resolution
    # (a real ANTHROPIC_API_KEY in the environment, then an `ant auth login`
    # profile) - see AnthropicLlm.__init__.
    anthropic_api_key: str = ""

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

    # Retrieval
    vector_store: str = "local"
    vector_store_path: str = "data/vector_store"
    embedding_dim: int = 1536

    # Guard rail described in the README: if the model declares more than this
    # fraction of the retrieved passages irrelevant, there was not enough
    # on-topic evidence to grade and we return '-' rather than a thin letter.
    irrelevant_ref_limit: float = 0.75

    # Maps a scraped `review_documents.source_name` onto a source type so the
    # passage header can distinguish an owner report from a launch editorial.
    # Stands in for the optional `source_type` column (spec section 12).
    source_types: dict[str, str] = {
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


@lru_cache
def get_settings() -> Settings:
    return Settings()
