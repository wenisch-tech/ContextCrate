# Harvex

Harvex is a self-hosted crawling, extraction, and retrieval platform. It fetches raw pages, stores immutable artifacts, normalizes text and metadata with JSoup, creates deterministic chunks, extracts structured matches, and indexes documents/chunks in Lucene or OpenSearch for BM25-style search.

## What it can do today

- Manage crawl jobs with scope, politeness, reliability, rendering, retention, and output settings.
- Fetch pages with Java `HttpClient` or Playwright/Chromium browser rendering for JavaScript-heavy pages.
- Apply SSRF protections, robots.txt enforcement, per-host delay, retry limits, leases, and dead-letter handling.
- Store raw fetch artifacts on the filesystem or S3-compatible storage.
- Parse HTML into normalized documents with titles, language, description, author, headings, Open Graph metadata, links, and clean body text.
- Split normalized documents into deterministic overlapping chunks.
- Run extraction rules over chunk text. The first rule types are built-in IP address detection and Java regular expressions.
- Store extraction results with rule, run, document, chunk, offsets, matched value, and context.
- Rebuild extraction results for a run or document after rule changes.
- Index document and chunk records in Lucene or OpenSearch.
- Search indexed content with BM25-style ranking through the API and the landing page.
- Browse jobs, runs, documents, extraction rules/results, queue state, index health, and operations in the administration UI.
- Use REST/OpenAPI endpoints, metrics, audit logging, API keys, and portable backup/restore.

Harvex is not a complete RAG system yet. It has the crawling, chunking, extraction, indexing, and retrieval substrate. LLM generation, embeddings, hybrid retrieval, feedback, and reranking are roadmap items.

## Pipeline

```mermaid
flowchart LR
  J[Crawl job] --> F[Frontier]
  F --> W1[Fetch work]
  W1 --> C[HTTP or browser crawler]
  C --> A[(Artifact store)]
  C --> W2[Parse work]
  W2 --> P[JSoup parser]
  P --> D[(Documents and chunks)]
  P --> F
  D --> W3[Extract work]
  W3 --> E[Extraction rules]
  E --> R[(Extraction results)]
  D --> W4[Index work]
  W4 --> I[Lucene or OpenSearch]
  I --> S[BM25 search]
```

The crawler writes raw artifacts before database records point at them. The parser creates canonical normalized documents and deterministic chunks. Extraction is derived work over `DocumentChunk.content`; it does not mutate crawled artifacts, normalized document bodies, chunk IDs, or search-index contracts. Indexing writes equivalent document/chunk fields to Lucene or OpenSearch, and search reads the active backend with the same API contract.

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

## Key API surfaces

Swagger UI is available at `/api`; OpenAPI JSON is available at `/v3/api-docs`.

- Jobs and runs: `GET/POST /api/v1/jobs`, `POST /api/v1/jobs/{id}/runs`, `GET /api/v1/runs`
- Documents and chunks: `GET /api/v1/documents`, `GET /api/v1/documents/{id}/chunks`
- Search: `GET /api/v1/search?q=...&kind=chunk&limit=20`
- Extraction rules: `GET/POST /api/v1/extraction-rules`, `PUT/DELETE /api/v1/extraction-rules/{id}`
- Extraction results: `GET /api/v1/extraction-results`, `GET /api/v1/extraction-rules/{id}/results`
- Rebuild extraction: `POST /api/v1/runs/{id}/extractions/rebuild`, `POST /api/v1/documents/{id}/extractions/rebuild`
- Operations: `GET /api/v1/system`, `GET /api/v1/queue/dead-letters`, `POST /api/v1/index/commit`
- Backups: `POST /api/v1/backups`

## Current scope

Version 1 focuses on durable crawling, normalization, chunking, structured extraction, BM25 retrieval, operations, and portability. Vector embeddings, hybrid retrieval, answer generation, feedback collection, reranking, and learning-to-rank controls are intentionally deferred.

Documentation lives in [`docs`](docs/index.md). Harvex is licensed under AGPL-3.0.
