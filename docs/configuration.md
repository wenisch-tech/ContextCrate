# Configuration

## Backend selection

| Property | Values | Standalone | Distributed |
|---|---|---|---|
| `harvex.queue.backend` | `local`, `rabbitmq` | local | rabbitmq |
| `harvex.database.backend` | `h2`, `postgresql` | h2 | postgresql |
| `harvex.artifacts.backend` | `filesystem`, `s3` | filesystem | s3 |
| `harvex.index.backend` | `lucene`, `opensearch` | lucene | opensearch |
| `harvex.role` | `all`, stage role | all | control-plane |

Local queue and file-backed H2 require `role=all`. Lucene requires a singleton indexer and a local or correctly shared index path. Filesystem artifacts need a shared volume when accessed by multiple processes.

## Crawl configuration

- **Scope:** seed, allowed hosts, subdomains, glob includes/excludes, maximum depth/pages, and sitemap intent.
- **Politeness:** identifying user agent/contact, robots enforcement, per-host concurrency, delay, and request timeout.
- **Reliability:** attempts, backoff, maximum body size, content deduplication, and `HTTP_ONLY`, `BROWSER_ONLY`, or `AUTO` rendering.
- **Output:** raw retention, content/removal selectors, chunk size/overlap, and logical index name.

Robots enforcement is on by default. Private, loopback, link-local, metadata, and multicast destinations are blocked. `harvex.crawler.allow-private-networks=true` exists for controlled intranet deployments and tests; use it only with trusted job administrators.

## Environment examples

Spring properties map to uppercase underscore environment variables. Distributed connection variables are shown in `compose.distributed.yml`. AWS credentials use the standard AWS SDK provider chain.

## Embeddings

Embeddings default to the local ONNX provider. See [Embeddings](embeddings.md) for local cache/offline model configuration, OpenAI-compatible endpoints, and retrieval-mode settings.

## Answer generation

Answer generation is disabled by default. Set `HARVEX_ANSWERING_ENABLED=true` and configure `HARVEX_ANSWERING_OPENAI_COMPATIBLE_BASE_URL`, `HARVEX_ANSWERING_OPENAI_COMPATIBLE_MODEL`, and a secret-backed `HARVEX_ANSWERING_OPENAI_COMPATIBLE_API_KEY`. See [RAG answer generation](answers.md) for limits, SSE behavior, and safety controls.
