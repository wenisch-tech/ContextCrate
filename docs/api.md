# API

Swagger UI is available at `/api`; OpenAPI JSON is available at `/v3/api-docs`. Browser sessions use form authentication and automation may use HTTP Basic authentication over TLS.

Key endpoints:

- `GET/POST /api/v1/jobs`
- `GET/PUT /api/v1/jobs/{id}`
- `POST /api/v1/jobs/{id}/runs`
- `POST /api/v1/jobs/runs/{id}/pause|resume|cancel`
- `GET /api/v1/system`
- `GET /api/v1/search?q=...`
- `GET /api/v1/documents`
- `GET /api/v1/documents/{id}/chunks`
- `GET/POST /api/v1/extraction-rules`
- `PUT/DELETE /api/v1/extraction-rules/{id}`
- `GET /api/v1/extraction-rules/{id}/results`
- `POST /api/v1/extraction-rules/test`
- `GET /api/v1/extraction-results`
- `POST /api/v1/runs/{id}/extractions/rebuild`
- `POST /api/v1/documents/{id}/extractions/rebuild`
- `GET /api/v1/queue/dead-letters`
- `POST /api/v1/queue/dead-letters/{id}/requeue`
- `POST /api/v1/index/commit`
- `POST /api/v1/index/rebuild`
- `POST /api/v1/answers` (SSE)
- `POST /api/v1/backups`

Pipeline envelopes are schema-versioned and contain ID, stage, payload reference, correlation ID, idempotency key, priority, attempt count, and creation time.

Search supports lexical BM25, semantic-vector, and RRF-hybrid retrieval over indexed document and chunk fields. `GET /api/v1/search` accepts `q`, optional `limit`, `runId`, `kind` (`document` or `chunk`), and `mode` (`lexical`, `semantic`, or `hybrid`). Results include selected mode, final score, component scores, source URL, document ID, chunk ID when applicable, chunk ordinal, and a snippet. `POST /api/v1/index/rebuild` rebuilds the derived vector index from canonical records.

`POST /api/v1/answers` accepts a question plus optional chunk retrieval filters and client-supplied history, and streams `sources`, `delta`, `complete`, and `error` SSE events. It is disabled until an OpenAI-compatible chat endpoint is configured. See [RAG answer generation](answers.md).

Extraction rules are reusable across runs. `IP_ADDRESS` ignores the pattern field and validates IPv4/IPv6 candidates; `REGEX` requires a valid Java regular expression. `DELETE /api/v1/extraction-rules/{id}` disables the rule rather than removing historical references. `GET /api/v1/extraction-rules/{id}/results` is the direct rule-scoped way to list matches. Generic extraction result queries are paginated and accept optional `runId`, `documentId`, `chunkId`, `ruleId`, and `value` filters.
