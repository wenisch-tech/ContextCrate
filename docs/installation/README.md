# Installation

ContextCrate can run as a compact standalone application or as independently scalable distributed roles. Choose the smallest deployment that fits the workload, but use Kubernetes for production.

| Method | Use it for | Production suitability |
|---|---|---|
| [Docker](docker.md) | Fast local evaluation from a published image | Small standalone deployments |
| [Docker Compose](compose.md) | Local development, standalone evaluation, or distributed evaluation | Standalone only; distributed Compose is evaluation-only |
| [JAR](jar.md) | Local Java development and direct JVM operation | Small standalone deployments |
| [Kubernetes](kubernetes.md) | Managed, resilient, or scalable installations | Recommended |

All methods require a non-default `CONTEXTCRATE_ADMIN_PASSWORD`. The default initial administrator is `admin@contextcrate.local`; change the email with `CONTEXTCRATE_ADMIN_EMAIL` when needed.

## Deployment profiles

The standalone profile runs every pipeline stage in one process using H2, filesystem artifacts, and Lucene. Persist `/app/data` because it contains application data and the local embedding-model cache.

The distributed profile separates the control plane, source workers, browser crawler, parser, and indexer. It uses PostgreSQL, RabbitMQ, S3-compatible object storage, and OpenSearch. Use independently operated backing services, durable volumes, Kubernetes Secrets, and an Ingress with TLS for production.

See [Deployment Notes](../deployment.md), [Configuration](../configuration.md), and [Operations](../operations.md) for topology, environment variables, backups, and scaling guidance.
