# Docker Compose

The repository provides two Compose configurations. Both are intended for local development or evaluation; use [Kubernetes](kubernetes.md) for production.

## Standalone Compose

`compose.yml` builds the current checkout and runs all pipeline stages in one container with a named data volume.

Install JDK 25 or newer because the Dockerfile copies the packaged JAR from `target/`.

```bash
./mvnw -DskipTests package
CONTEXTCRATE_ADMIN_PASSWORD=change-me docker compose up --build
```

Open `http://localhost:8080` and sign in as `admin@contextcrate.local` with the password you supplied. Stop the application with `docker compose down`; omit `--volumes` to preserve `contextcrate-data`.

The standalone volume includes H2, filesystem artifacts, Lucene, and the local model cache. It is appropriate for development and moderate single-instance workloads.

## Distributed Compose

`compose.distributed.yml` starts ContextCrate roles with PostgreSQL, RabbitMQ, MinIO, and OpenSearch in one local Compose project. It is an evaluation topology, not a production installation: its service credentials and disabled OpenSearch security are deliberately convenient for local use.

Build the JAR once before starting the stack if it is not already present in `target/`.

```bash
./mvnw -DskipTests package
CONTEXTCRATE_ADMIN_PASSWORD=change-me docker compose -f compose.distributed.yml up
```

The control plane is available at `http://localhost:8080`. RabbitMQ management is exposed on `http://localhost:15672`, and MinIO Console on `http://localhost:9001`.

Stop the stack with:

```bash
docker compose -f compose.distributed.yml down
```

For a clean local reset, explicitly remove the named Compose volumes after confirming that no data needs to be retained. Production deployments should use managed or independently operated PostgreSQL, RabbitMQ, S3-compatible object storage, and OpenSearch instead.
