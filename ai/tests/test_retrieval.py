"""Retrieval: query construction, product scoping, and the stand-in store."""

from __future__ import annotations

from app.retrieval.embedder import DeterministicEmbedder
from app.retrieval.query import build_query, top_priorities
from app.retrieval.store import SEARCH_SQL, LocalVectorStore


def test_search_sql_filters_by_product_id():
    """Without this filter, passages about other phones that happen to be
    semantically similar get graded as if they described the candidate."""
    assert "rd.product_id = %(product_id)s" in SEARCH_SQL
    assert "WHERE" in SEARCH_SQL
    assert "ORDER BY rc.embedding <=>" in SEARCH_SQL
    assert "LIMIT" in SEARCH_SQL


def test_local_store_filters_by_product_id(store):
    embedder = DeterministicEmbedder(dim=64)
    results = store.search(812, embedder.embed("battery life"), k=10)

    assert results, "fixture has passages for product 812"
    assert {chunk.product_id for chunk in results} == {812}
    assert 9001 not in {chunk.chunk_id for chunk in results}


def test_local_store_returns_at_most_k(store):
    embedder = DeterministicEmbedder(dim=64)
    assert len(store.search(812, embedder.embed("battery"), k=2)) == 2


def test_local_store_is_empty_when_nothing_is_ingested(tmp_path):
    empty = LocalVectorStore(tmp_path / "nothing", DeterministicEmbedder(dim=64))
    assert empty.search(812, [0.0] * 64, k=5) == []


def test_unknown_product_returns_nothing(store):
    assert store.search(4242, [0.0] * 64, k=5) == []


def test_top_priorities_takes_the_three_highest_weights():
    priorities = {"battery": 5, "camera": 4, "longevity": 4, "performance": 2}
    assert top_priorities(priorities) == ["battery", "camera", "longevity"]


def test_top_priorities_breaks_ties_deterministically():
    assert top_priorities({"value": 3, "audio": 3, "display": 3}) == [
        "audio",
        "display",
        "value",
    ]


def test_query_combines_priorities_pain_points_and_use_cases(assess_request):
    query = build_query(assess_request.user_context)

    assert "battery" in query
    assert "camera" in query
    assert "longevity" in query
    assert "performance" not in query, "only the top three priorities are used"
    assert "drains by lunchtime" in query
    assert "photography" in query and "gaming" in query


def test_query_survives_missing_optional_context(assess_request):
    assess_request.user_context.preferences.pain_points = None
    assess_request.user_context.owned_device.use_cases = []
    assert build_query(assess_request.user_context).strip()


def test_embedder_is_deterministic_and_normalised():
    embedder = DeterministicEmbedder(dim=64)
    first = embedder.embed("battery life is poor")
    assert first == embedder.embed("battery life is poor")
    assert abs(sum(value * value for value in first) - 1.0) < 1e-9
