# REST API

Swagger UI is served at `/api`; OpenAPI JSON is at `/v3/api-docs`. Content APIs use an explicit crate path. Authenticate with a session, HTTP Basic, or `X-API-KEY`.

## Crates and members

| Method | Path | Authority |
|---|---|---|
| GET, POST | `/api/v1/crates` | Membership list / creation policy |
| GET, PUT | `/api/v1/crates/{crateId}` | Viewer / Owner |
| GET, PUT | `/api/v1/crates/{crateId}/members` | Viewer / Owner |
| DELETE | `/api/v1/crates/{crateId}/members/{userId}` | Owner |
| POST | `/api/v1/crates/{crateId}/archive` | Owner |
| POST | `/api/v1/crates/{crateId}/restore` | Owner |
| POST | `/api/v1/crates/{crateId}/purge` | Owner |

Purge body: `{"confirmation":"Exact crate name"}`.

## Content

| Capability | Path |
|---|---|
| Jobs | `/api/v1/crates/{crateId}/jobs` |
| Runs | `/api/v1/crates/{crateId}/runs` |
| Documents | `/api/v1/crates/{crateId}/documents` |
| Search | `/api/v1/crates/{crateId}/search?q=...&mode=hybrid` |
| Streaming answers | `/api/v1/crates/{crateId}/answers` |
| Extraction rules/results | `/api/v1/crates/{crateId}/extraction-rules`, `/extraction-results` |
| Index health/rebuild | `/api/v1/crates/{crateId}/index` |
| Audit | `/api/v1/crates/{crateId}/audit` |

Search modes are `lexical`, `semantic`, and `hybrid`. Answers accept JSON and return server-sent events named `sources`, `delta`, `complete`, or `error`.

```bash
curl -H "X-API-KEY: $KEY" \
  "http://localhost:8080/api/v1/crates/$CRATE/search?q=retention&kind=chunk&mode=hybrid&limit=20"
```

## Settings

- `GET/PUT /api/v1/crates/{crateId}/settings/rag`
- `GET/PUT /api/v1/crates/{crateId}/settings/providers`

Provider responses never contain stored API keys. Submitting a blank key preserves the current secret. Changing embedding configuration schedules a versioned rebuild.

## Keys and portability

- `GET/POST /api/v1/me/api-keys`
- `DELETE /api/v1/me/api-keys/{id}`
- `GET/POST /api/v1/crates/{crateId}/api-keys`
- `DELETE /api/v1/crates/{crateId}/api-keys/{id}`
- `POST /api/v1/crates/{crateId}/exports`
- `POST /api/v1/crate-imports/validate`
- `POST /api/v1/crate-imports`

## Administration

Global endpoints are under `/api/v1/admin`: users, creation policy, infrastructure health, dead letters, and temporary elevations. They do not expose crate documents or search without elevation.

Errors use the standard API exception envelope and never redirect a REST client to another crate. The previous unscoped `/api/v1/jobs`, `/search`, `/answers`, and `/backups` contracts were removed.
