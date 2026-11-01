# Installation and administration

This section is for administrators: it covers installing, configuring, securing and operating ContextCrate. If you only want to use an existing installation, see [Using ContextCrate](../usage.md). For the concepts behind the components, see [Architecture](../architecture.md).

## The administrator's path

1. **Choose** a deployment profile and install method (below).
2. **Install** with [Docker](docker.md), [Compose](compose.md), the [JAR](jar.md) or [Kubernetes](kubernetes.md).
3. **Configure** backends, ingestion defaults and model providers: [Configuration](../configuration.md), [Embeddings](../embeddings.md), [Reranking](../reranking.md), [Answers](../answers.md).
4. **Secure** the installation: [Security](../security.md), single sign-on with [OIDC](../operations/oidc.md), and the [authorization model](../authorization.md).
5. **Administer** users, crate creation, model policy and shared models: [Administration](../operations/administration.md).
6. **Operate**: health, metrics and scaling in [Operations](../operations.md), topology in [Deployment](../deployment.md), crate [export and recovery](../backup-migration.md), [upgrades](../upgrade-from-harvex.md), and [Troubleshooting](../troubleshooting.md).

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

## What to read next

| I want to... | Page |
|---|---|
| Understand the topology of distributed mode and scale workers | [Deployment](../deployment.md) |
| Set backends, ingestion options and environment variables | [Configuration](../configuration.md) |
| Harden the installation | [Security](../security.md) |
| Add single sign-on | [OIDC](../operations/oidc.md) |
| Create users, set the crate-creation policy, share models | [Administration](../operations/administration.md) |
| Monitor health and metrics, import the Grafana dashboard | [Operations](../operations.md) |
| Back up, move or recover crates | [Export, import and recovery](../backup-migration.md) |
| Fix a startup or runtime problem | [Troubleshooting](../troubleshooting.md) |
| Start using the installation | [Using ContextCrate](../usage.md) |
