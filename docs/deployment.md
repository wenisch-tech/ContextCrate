# Deployment

## Standalone

Run the JAR or `compose.yml`. Persist `/app/data`, set a strong admin password, and back up the volume. Standalone is appropriate for one instance and moderate crawls.

## Distributed

Use `compose.distributed.yml` for evaluation or `charts/harvex` for Kubernetes. Production PostgreSQL, RabbitMQ, object storage, and OpenSearch should be operated independently. Scale HTTP crawlers and parsers horizontally. Keep Lucene indexers at one replica; OpenSearch indexers may scale.

The Helm chart defaults to standalone. For distributed mode:

```yaml
profile: distributed
roles:
  all: { enabled: false, replicas: 0 }
  control-plane: { enabled: true, replicas: 1 }
  crawler-http: { enabled: true, replicas: 3 }
  crawler-browser: { enabled: true, replicas: 1 }
  parser: { enabled: true, replicas: 2 }
  indexer: { enabled: true, replicas: 2 }
```

Provide connection settings through `env` and `secrets`. Queue-aware KEDA scaling can target RabbitMQ queue length without changing the application.
