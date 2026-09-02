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

## Sources and ingestion jobs

A source owns its endpoint and credentials. Multiple ingestion jobs can reuse the source with
different scopes, refs, path filters, limits, and normalization settings.

### Website jobs

- **Scope:** seed, allowed hosts, subdomains, glob includes/excludes, maximum depth/pages, and sitemap intent.
- **Politeness:** identifying user agent/contact, robots enforcement, per-host concurrency, delay, and request timeout.
- **Reliability:** attempts, backoff, maximum body size, content deduplication, and `HTTP_ONLY`, `BROWSER_ONLY`, or `AUTO` rendering.
- **Output:** raw retention, content/removal selectors, chunk size/overlap, and logical index name.

Robots enforcement is on by default. Private, loopback, link-local, metadata, and multicast destinations are blocked. `contextcrate.crawler.allow-private-networks=true` exists for controlled intranet deployments and tests; use it only with trusted job administrators.

### Form authentication and SSO

HTTPS authentication is configured on the ingestion job, so different jobs from one source can use different access paths. Select **FORM** for a login page, or **Keycloak / OAuth2** for a Keycloak server, realm, and client. ContextCrate follows the identity-provider flow, submits configured credential steps with hidden form state, follows the callback, and keeps cookies only for that ingestion run. Authentication logs record the redacted endpoint, detected credential step, HTTP status, and redirect path; they never include passwords, OIDC state, authorization codes, or session codes.

The supported standard flows do not include MFA, CAPTCHA, consent pages, or required-action workflows. Authentication redirects may leave the configured crawl hosts, but every destination is still checked by the crawler's private-network and SSRF protections; identity-provider pages are not added to the crawl frontier.

### Git jobs

Git sources accept repository URLs. Each Git ingestion job holds optional username/token credentials,
the remote default branch or a branch, tag, or reachable commit, plus include/exclude globs. The
defaults are 10,000 files, 1 MiB per file, and all eligible repository paths.

Only valid UTF-8 `.md`, `.markdown`, and `.txt` files are normalized. SSH, filesystem remotes,
symlinks, submodules, Git LFS objects, repository history, webhooks, scheduling, and incremental
synchronization are not supported yet. There is no repository-wide transfer quota. Each run
acquires a complete selected-ref snapshot.

Git tokens are removed from API responses, logs, audit messages, and exports, but they are stored
as plaintext in the ingestion-job configuration JSON. Protect database access and backups accordingly.

## TLS certificate validation

`CONTEXTCRATE_TLS_TRUST_ALL_CERTIFICATES` (`contextcrate.tls.trust-all-certificates`, default
`false`) disables TLS certificate and hostname validation process-wide: model providers
(answering, embedding, reranking), OIDC, Git and web-crawler ingestion, robots.txt fetches,
Keycloak token requests, the MCP client, S3, RabbitMQ (when `spring.rabbitmq.ssl.enabled=true`),
and Postgres. It ORs on top of the existing per-job Git/crawler "ignore TLS certificate errors"
checkboxes and `CONTEXTCRATE_SECURITY_OIDC_TRUST_ALL_CERTIFICATES` — those flags keep working
independently for narrower cases. Only enable it for internal/self-signed CAs you already trust;
see [Security](security.md) for the risk and a safer alternative.

## Environment examples

Spring properties map to uppercase underscore environment variables. Distributed connection variables are shown in `compose.distributed.yml`. AWS credentials use the standard AWS SDK provider chain.

## Embeddings

Embeddings default to the local ONNX provider. For OpenAI-compatible endpoints,
`CONTEXTCRATE_EMBEDDINGS_OPENAI_COMPATIBLE_AUTOMATIC_LIMIT_RECOVERY` defaults to `true` and
lets each crate automatically recover from generic context-limit errors. The crate Settings page
can override it. See [Embeddings](embeddings.md) for local cache/offline model configuration,
OpenAI-compatible endpoints, and retrieval-mode settings.

## Answer generation

Answer generation is disabled by default. Set `CONTEXTCRATE_ANSWERING_ENABLED=true` and configure `CONTEXTCRATE_ANSWERING_OPENAI_COMPATIBLE_BASE_URL`, `CONTEXTCRATE_ANSWERING_OPENAI_COMPATIBLE_MODEL`, and a secret-backed `CONTEXTCRATE_ANSWERING_OPENAI_COMPATIBLE_API_KEY`. See [RAG answer generation](answers.md) for limits, SSE behavior, and safety controls.

After the provider is configured, use the crate-specific authenticated **Settings** page to choose strict knowledge-base-only answers, history permission, citation behavior, default retrieval mode, and source count. Those runtime policies are stored in the database; provider connection details remain environment/secret configuration.

Each crate Settings page can override embedding and answer-provider values such as provider kind, enabled state, model, endpoint URL, dimensions, local model paths, and API keys. Database overrides take precedence over environment values. API keys entered in Settings are stored in the ContextCrate database; protect database access and backups with encryption.

## Reranking

Reranking is disabled by default and can rescore the retrieval candidate pool before results are
shown or used for RAG. Configure a local ONNX cross-encoder or Cohere-compatible endpoint with
`CONTEXTCRATE_RERANKING_*`, or override every setting per crate in the UI. See [Reranking](reranking.md).
