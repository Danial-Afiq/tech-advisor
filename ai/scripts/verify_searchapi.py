"""Read-only semantic retrieval smoke test; never calls SearchAPI or an LLM.

python -m scripts.verify_searchapi --product-id 812 --query "battery life"
"""
import argparse
import json

from psycopg_pool import ConnectionPool
from app.config import Settings
from app.retrieval.embedder import build_embedder
from app.retrieval.store import PgVectorStore


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--product-id", type=int, required=True)
    parser.add_argument("--query", default="battery life")
    parser.add_argument("--other-product-id", type=int, default=987654321)
    args = parser.parse_args()
    if args.product_id == args.other_product_id:
        parser.error("Choose two different product IDs")
    settings = Settings()
    embedder = build_embedder(settings.embedder, settings.embedding_dim, settings.embedding_model_path)
    with ConnectionPool(settings.dsn, min_size=1, max_size=2) as pool:
        store = PgVectorStore(pool, embedder_name=embedder.name)
        query = embedder.embed(args.query)
        chunks = store.search(args.product_id, query, k=12)
        other = store.search(args.other_product_id, query, k=12)
        with pool.connection() as conn:
            owned = {r[0] for r in conn.execute(
                "SELECT c.id FROM review_chunks c JOIN review_documents d ON d.id=c.review_document_id "
                "WHERE d.product_id=%s AND d.provider='SEARCHAPI_GOOGLE_SHOPPING'", (args.product_id,))}
        assert any(c.chunk_id in owned for c in chunks), "No SearchAPI evidence retrieved for this product"
        assert not owned.intersection(c.chunk_id for c in other), "Cross-product evidence leak"
        print(json.dumps({"product_id": args.product_id, "embedder": embedder.name,
                          "query": args.query, "chunks": [
                              {"chunk_id": c.chunk_id, "source": c.source_name, "text": c.chunk_text}
                              for c in chunks], "other_product_id": args.other_product_id,
                          "cross_product_leak": False}, indent=2))


if __name__ == "__main__":
    main()
