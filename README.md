# Harvex

Harvex is a self-hosted crawling and indexing platform. It fetches raw pages, stores immutable artifacts, extracts normalized text and metadata with JSoup, creates deterministic chunks, and indexes them in Lucene or OpenSearch.

## Standalone quick start

Prerequisites: Java 25 and Maven 3.9+.

```bash
./mvnw verify
./mvnw spring-boot:run
```

Open [http://localhost:8080](http://localhost:8080) and sign in with `admin@harvex.local` / `admin`. Change `HARVEX_ADMIN_PASSWORD` before exposing the service.

Standalone mode needs no external services. H2, the durable work queue, raw artifacts, and Lucene are stored below `./data`.

```bash
docker run --rm -p 8080:8080 -v harvex-data:/app/data \
  -e HARVEX_ADMIN_PASSWORD=change-me ghcr.io/wenisch-tech/harvex:latest
```

## Distributed mode

Distributed mode replaces the local adapters with PostgreSQL, RabbitMQ, S3-compatible storage, and OpenSearch. Each stage uses the same application image with a different `HARVEX_ROLE`.

```bash
docker compose -f compose.distributed.yml up
```

Backends can be mixed safely; invalid single-writer/single-process combinations fail fast. See [the configuration guide](docs/configuration.md).

## Current scope

Version 1 includes job management, scope/politeness/reliability/output configuration, HTTP and Playwright fetching, SSRF and robots protection, durable retries, parsing, metadata, chunking, indexing, a dark administration UI, REST/OpenAPI, metrics, and portable backup/restore. Retrieval, vectors, feedback, and reranking are intentionally deferred.

Documentation lives in [`docs`](docs/index.md). Harvex is licensed under AGPL-3.0.
