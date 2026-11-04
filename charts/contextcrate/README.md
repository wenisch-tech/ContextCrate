# ContextCrate Helm chart

Standalone is the default and uses one PVC. For distributed mode set `profile=distributed`, disable `roles.all`, enable the remaining roles, and provide PostgreSQL, RabbitMQ, S3, and a search service (OpenSearch by default; or `CONTEXTCRATE_INDEX_BACKEND=elasticsearch|qdrant|pgvector` with `CONTEXTCRATE_INDEX_ENDPOINT`, `CONTEXTCRATE_INDEX_API_KEY` or `CONTEXTCRATE_INDEX_USERNAME`/`CONTEXTCRATE_INDEX_PASSWORD`) connection environment variables.

Lucene and H2 require a singleton. Filesystem artifacts require shared storage when multiple processes access them.

Embeddings default to a locally downloaded ONNX model cached below `/app/data/models`. For air-gapped use, mount a compatible model bundle through `embeddings.local.modelPathMount`; for a hosted provider, select `embeddings.provider=openai-compatible` and inject its API key via `embeddings.openaiCompatible.apiKeySecret`.
