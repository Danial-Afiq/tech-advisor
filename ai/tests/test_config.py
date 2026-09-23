"""The root `.env` is the single source of truth for secrets and anything a
second service also needs.

These tests need no Anthropic credential. They prove where configuration is
read from and that the credential reaches the SDK - not that a model call
succeeds, which nothing here can establish.
"""

from __future__ import annotations

import os
from pathlib import Path

import pytest

from app.config import ENV_FILE, REPO_ROOT, Settings
from app.llm import AnthropicLlm


def test_env_file_is_the_repo_root_env() -> None:
    """The repo root is two levels up from app/config.py: ai/app -> ai -> root."""
    assert ENV_FILE == REPO_ROOT / ".env"
    assert (REPO_ROOT / "docker-compose.yml").is_file(), (
        "REPO_ROOT does not look like the repo root"
    )


def test_env_file_resolution_ignores_the_working_directory(tmp_path: Path) -> None:
    """The regression this guards: `env_file=".env"` was CWD-relative, so it
    silently found nothing when uvicorn was started from anywhere but ai/."""
    before = ENV_FILE
    cwd = os.getcwd()
    try:
        os.chdir(tmp_path)
        from importlib import reload

        import app.config

        reload(app.config)
        assert app.config.ENV_FILE == before
    finally:
        os.chdir(cwd)


def test_root_env_declares_the_ai_layer_variables() -> None:
    """Blank is fine; absent is not. Compose interpolates both of these, and an
    absent AI_SERVICE_TOKEN is what silently disabled /assess authentication."""
    if not ENV_FILE.is_file():
        pytest.skip("no .env checked out locally; .env.example covers the contract")
    declared = {
        line.split("=", 1)[0].strip()
        for line in ENV_FILE.read_text(encoding="utf-8").splitlines()
        if "=" in line and not line.lstrip().startswith("#")
    }
    assert {"ANTHROPIC_API_KEY", "AI_SERVICE_TOKEN"} <= declared


def test_settings_reads_the_credential_from_an_env_file(tmp_path: Path) -> None:
    env = tmp_path / ".env"
    env.write_text(
        "ANTHROPIC_API_KEY=sk-ant-not-a-real-key\nAI_SERVICE_TOKEN=shared\n",
        encoding="utf-8",
    )
    settings = Settings(_env_file=env)
    assert settings.anthropic_api_key == "sk-ant-not-a-real-key"
    assert settings.ai_service_token == "shared"


def test_settings_tolerates_other_services_variables(tmp_path: Path) -> None:
    """The root .env also carries backend and frontend variables. `extra=ignore`
    is what keeps those from raising a ValidationError here."""
    env = tmp_path / ".env"
    env.write_text(
        "POSTGRES_PASSWORD=devpassword\n"
        "VITE_API_BASE_URL=http://localhost:8080\n"
        "INGESTION_ANCHOR=2026-09-17T05:00:00Z\n"
        "K=7\n",
        encoding="utf-8",
    )
    settings = Settings(_env_file=env)
    assert settings.k == 7  # an AI knob still overridable from the environment
    assert settings.llm_model == "claude-opus-5"  # untouched code default


def test_credential_reaches_the_anthropic_client() -> None:
    """The bug this guards: the key was read into no field at all, and the SDK
    was constructed with no api_key, so ai/.env could never have worked."""
    llm = AnthropicLlm(Settings(anthropic_api_key="sk-ant-not-a-real-key"))
    assert llm.client.api_key == "sk-ant-not-a-real-key"


def test_relative_vector_store_path_resolves_against_the_package_root() -> None:
    """The regression this guards cost a live debugging round: the path was
    CWD-relative, so starting uvicorn from the repo root found no store,
    retrieved nothing, and degraded every assessment to '-' with
    NO_PASSAGES_RETRIEVED - no error, no log, nothing pointing at the cause."""
    from app.config import AI_ROOT

    settings = Settings(_env_file=None, vector_store_path="data/vector_store")
    assert settings.resolved_vector_store_path == AI_ROOT / "data" / "vector_store"
    assert settings.resolved_vector_store_path.is_absolute()
    # AI_ROOT is the `ai` package root - `/srv` in the container, which is
    # where docker-compose mounts the store.
    assert (AI_ROOT / "app" / "main.py").is_file()


def test_absolute_vector_store_path_is_left_alone(tmp_path: Path) -> None:
    settings = Settings(_env_file=None, vector_store_path=str(tmp_path / "store"))
    assert settings.resolved_vector_store_path == tmp_path / "store"
