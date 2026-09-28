"""Hermetic embedding route checks; never builds an assessor or an LLM."""
import pytest
from fastapi.testclient import TestClient
from app import main
from app.config import get_settings
from app.retrieval.embedder import DeterministicEmbedder


@pytest.fixture
def client(settings, monkeypatch):
    def forbidden(*args, **kwargs):
        raise AssertionError("Embedding must never initialize an LLM")
    monkeypatch.setattr(main, "build_llm", forbidden)
    monkeypatch.setattr(main, "build_assessor", forbidden)
    main.app.dependency_overrides[get_settings] = lambda: settings
    main.app.dependency_overrides[main.get_embedder] = lambda: DeterministicEmbedder(512)
    yield TestClient(main.app), settings
    main.app.dependency_overrides.clear()


def test_batch_vectors_share_retrieval_space(client):
    http, _ = client
    texts = ["battery life lasts all day", "camera struggles at night"]
    response = http.post("/internal/embed", json={"texts": texts})
    assert response.status_code == 200
    assert response.json() == {"embedder": "deterministic", "dimension": 512,
                               "vectors": [DeterministicEmbedder(512).embed(t) for t in texts]}


def test_auth(client):
    http, settings = client
    settings.ai_service_token = "test-only-token"
    assert http.post("/internal/embed", json={"texts": ["battery"]}).status_code == 401
    assert http.post("/internal/embed", json={"texts": ["battery"]},
                     headers={"Authorization": "Bearer test-only-token"}).status_code == 200


@pytest.mark.parametrize("texts", [[], [" "], ["x" * 8001], ["battery"] * 101])
def test_bounds(client, texts):
    assert client[0].post("/internal/embed", json={"texts": texts}).status_code == 422


def test_model_batch_path_and_sanitized_failure(client):
    class BatchEmbedder:
        name = "test-batch"
        dim = 2
        def embed_many(self, texts):
            assert texts == ["one", "two"]
            return [[1.0, 0.0], [0.0, 1.0]]
    main.app.dependency_overrides[main.get_embedder] = BatchEmbedder
    response = client[0].post("/internal/embed", json={"texts": ["one", "two"]})
    assert response.json()["vectors"] == [[1.0, 0.0], [0.0, 1.0]]
    class Broken(BatchEmbedder):
        def embed_many(self, texts):
            raise RuntimeError("sensitive provider response")
    main.app.dependency_overrides[main.get_embedder] = Broken
    response = client[0].post("/internal/embed", json={"texts": ["one"]})
    assert response.status_code == 503
    assert "sensitive" not in response.text
