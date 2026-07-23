# Configuration

## Backend selection

| Property | Values | Standalone | Distributed |
|---|---|---|---|
| `contextcrate.queue.backend` | `local`, `rabbitmq` | local | rabbitmq |
| `contextcrate.database.backend` | `h2`, `postgresql` | h2 | postgresql |
| `contextcrate.artifacts.backend` | `filesystem`, `s3` | filesystem | s3 |
| `contextcrate.index.backend` | `lucene`, `opensearch` | lucene | opensearch |
| `contextcrate.role` | `all`, stage role | all | control-plane |

Local queue and file-backed H2 require `role=all`. Lucene requires a singleton indexer and a local or correctly shared index path. Filesystem artifacts need a shared volume when accessed by multiple processes.

## Crawl configuration

- **Scope:** seed, allowed hosts, subdomains, glob includes/excludes, maximum depth/pages, and sitemap intent.
- **Politeness:** identifying user agent/contact, robots enforcement, per-host concurrency, delay, and request timeout.
- **Reliability:** attempts, backoff, maximum body size, content deduplication, and `HTTP_ONLY`, `BROWSER_ONLY`, or `AUTO` rendering.
- **Output:** raw retention, content/removal selectors, chunk size/overlap, and logical index name.

Robots enforcement is on by default. Private, loopback, link-local, metadata, and multicast destinations are blocked. `contextcrate.crawler.allow-private-networks=true` exists for controlled intranet deployments and tests; use it only with trusted job administrators.

## Environment examples

Spring properties map to uppercase underscore environment variables. Distributed connection variables are shown in `compose.distributed.yml`. AWS credentials use the standard AWS SDK provider chain.

## Embeddings

Embeddings default to the local ONNX provider. See [Embeddings](embeddings.md) for local cache/offline model configuration, OpenAI-compatible endpoints, and retrieval-mode settings.

## Answer generation

Answer generation is disabled by default. Set `CONTEXTCRATE_ANSWERING_ENABLED=true` and configure `CONTEXTCRATE_ANSWERING_OPENAI_COMPATIBLE_BASE_URL`, `CONTEXTCRATE_ANSWERING_OPENAI_COMPATIBLE_MODEL`, and a secret-backed `CONTEXTCRATE_ANSWERING_OPENAI_COMPATIBLE_API_KEY`. See [RAG answer generation](answers.md) for limits, SSE behavior, and safety controls.

After the provider is configured, use the crate-specific authenticated **Settings** page to choose strict knowledge-base-only answers, history permission, citation behavior, default retrieval mode, and source count. Those runtime policies are stored in the database; provider connection details remain environment/secret configuration.

Each crate Settings page can override embedding and answer-provider values such as provider kind, enabled state, model, endpoint URL, dimensions, local model paths, and API keys. Database overrides take precedence over environment values. API keys entered in Settings are stored in the ContextCrate database; protect database access and backups with encryption.
