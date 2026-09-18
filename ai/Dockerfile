FROM python:3.13-slim

WORKDIR /srv

COPY pyproject.toml ./
COPY app ./app
RUN pip install --no-cache-dir .

# The stand-in vector store. Ships empty; mount or bake in chunk files to
# exercise retrieval before pgvector is enabled.
COPY data ./data

EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
