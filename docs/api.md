# API

Swagger UI is available at `/api`; OpenAPI JSON is available at `/v3/api-docs`. Browser sessions use form authentication and automation may use HTTP Basic authentication over TLS.

Key endpoints:

- `GET/POST /api/v1/jobs`
- `GET/PUT /api/v1/jobs/{id}`
- `POST /api/v1/jobs/{id}/runs`
- `POST /api/v1/jobs/runs/{id}/pause|resume|cancel`
- `GET /api/v1/system`
- `GET /api/v1/documents`
- `GET /api/v1/documents/{id}/chunks`
- `GET /api/v1/queue/dead-letters`
- `POST /api/v1/queue/dead-letters/{id}/requeue`
- `POST /api/v1/index/commit`
- `POST /api/v1/backups`

Pipeline envelopes are schema-versioned and contain ID, stage, payload reference, correlation ID, idempotency key, priority, attempt count, and creation time.
