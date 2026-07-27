# Deployment

## Standalone

Run the JAR or `compose.yml`. Persist `/app/data`, set a strong admin password, and back up the volume. Standalone is appropriate for one instance and moderate crawls.

## Distributed

Use `compose.distributed.yml` for evaluation or `charts/contextcrate` for Kubernetes. Production PostgreSQL, RabbitMQ, object storage, and OpenSearch should be operated independently. Scale `source-web`, `source-git`, and parser workers horizontally. Keep Lucene indexers at one replica; OpenSearch indexers may scale.

The Helm chart defaults to standalone. For distributed mode:

```yaml
profile: distributed
roles:
  all: { enabled: false, replicas: 0 }
  control-plane: { enabled: true, replicas: 1 }
  source-web: { enabled: true, replicas: 3 }
  source-git: { enabled: true, replicas: 2 }
  crawler-browser: { enabled: true, replicas: 1 }
  parser: { enabled: true, replicas: 2 }
  indexer: { enabled: true, replicas: 2 }
```

Provide connection settings through `env` and `secrets`. Queue-aware KEDA scaling can target RabbitMQ queue length without changing the application.

## Embedding model storage

Persist `/app/data/models` so the default local model is downloaded only once. To run air-gapped, mount a compatible ONNX bundle at `/models` and set `CONTEXTCRATE_EMBEDDINGS_LOCAL_MODEL_PATH=/models`. Helm exposes the same choice through `embeddings.local.modelPathMount`. See [Embeddings](embeddings.md) for endpoint and rebuild configuration.

## Reranking model storage

Local rerankers use the same persistent model cache. Set `CONTEXTCRATE_RERANKING_LOCAL_CACHE_PATH`
or mount an offline ONNX cross-encoder and set `CONTEXTCRATE_RERANKING_LOCAL_MODEL_PATH`. A remote
reranker receives query and candidate text; configure its endpoint and secret through the
`CONTEXTCRATE_RERANKING_COHERE_COMPATIBLE_*` variables. See [Reranking](reranking.md).
