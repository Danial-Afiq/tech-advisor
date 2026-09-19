FROM python:3.13-slim

WORKDIR /srv

COPY pyproject.toml ./
COPY app ./app
RUN pip install --no-cache-dir .

# Bake the embedding model into the image.
#
# Without this the first /assess request downloads the model from HuggingFace:
# slow on the first call, and a hard failure anywhere without egress. Baking
# it also makes the image reproducible - the model cannot change under a
# running deployment.
#
# EMBEDDING_MODEL must match app.retrieval.embedder.MODEL2VEC_MODEL. The build
# asserts the dimension, so a model swap cannot silently disagree with the
# vector(512) column in migration V5. EMBEDDING_DIM is read by pydantic
# Settings at runtime too, which keeps the two in step by construction.
#
# EMBEDDING_MODEL_PATH makes the weights a plain directory rather than a
# HuggingFace cache entry. See bake_model.py for why that matters: it is what
# allows the redundant 124 MB ONNX copy to be skipped at all.
ARG EMBEDDING_MODEL=minishlab/potion-retrieval-32M
ARG EMBEDDING_DIM=512
ENV HF_HOME=/opt/hf \
    EMBEDDING_MODEL=${EMBEDDING_MODEL} \
    EMBEDDING_DIM=${EMBEDDING_DIM} \
    EMBEDDING_MODEL_PATH=/opt/model

COPY bake_model.py ./
RUN python bake_model.py && rm bake_model.py && du -sh /opt/model

# Never reach for the network at runtime: the model is already here, so a
# cache miss means a misconfiguration and should fail loudly at startup
# rather than quietly re-downloading on the request path.
ENV HF_HUB_OFFLINE=1

# The stand-in vector store. Ships with the demo corpus; ignored entirely
# when VECTOR_STORE=pgvector.
COPY data ./data

# Ingestion helper, so the corpus can be loaded from inside the container:
#   docker compose run --rm ai python -m scripts.ingest --truncate
COPY scripts ./scripts

EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
