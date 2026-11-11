# REST API

Swagger UI is served at `/api`; OpenAPI JSON is at `/v3/api-docs`. Content APIs use an explicit crate path. Authenticate with a session, HTTP Basic, `X-API-KEY`, or `Authorization: Bearer <token>` — the bearer form carries the same `cc_` API key and exists because most MCP clients send it.

Under `/api/v1/**` an unauthenticated request is answered with `401` and a `WWW-Authenticate` header, and an authenticated request for something the credential may not reach with `403`. Both return JSON. Browser pages still receive the form-login redirect.

## Crates and members

| Method | Path | Authority |
|---|---|---|
| GET, POST | `/api/v1/crates` | Membership list / creation policy |
| GET, PUT | `/api/v1/crates/{crateId}` | Viewer / Owner |
| GET, PUT | `/api/v1/crates/{crateId}/members` | Viewer / Owner |
| DELETE | `/api/v1/crates/{crateId}/members/{userId}` | Owner |
| POST | `/api/v1/crates/{crateId}/archive` | Owner |
| POST | `/api/v1/crates/{crateId}/restore` | Owner |
| POST | `/api/v1/crates/{crateId}/purge` | Owner (archived crate) |
| POST | `/api/v1/crates/{crateId}/delete` | Owner or global administrator |
| POST | `/api/v1/crates/{crateId}/duplicate` | Global administrator |

Duplicate body: `{"name":"New name","description":"…","storage":{…},"searchBackend":{…}}` (the last two as when creating a crate, optional). Copies sources, jobs, rules and settings including secrets; returns the new crate (`201`).

Purge and delete body: `{"confirmation":"Exact crate name"}`. `delete` works in any state (also for `PURGE_FAILED`), stops processing, and removes the crate, its documents, artifacts and search index in the background.

## Content

| Capability | Path |
|---|---|
| Sources | `/api/v1/crates/{crateId}/sources` |
| Ingestion jobs | `/api/v1/crates/{crateId}/sources/{sourceId}/ingestion-jobs` |
| Runs | `/api/v1/crates/{crateId}/runs` |
| Documents | `/api/v1/crates/{crateId}/documents` |
| Search | `/api/v1/crates/{crateId}/search?q=...&mode=hybrid` |
| Streaming answers | `/api/v1/crates/{crateId}/answers` |
| OpenAI-compatible chat | `/api/v1/crates/{crateId}/v1/chat/completions`, `/v1/models` |
| Extraction rules/results | `/api/v1/crates/{crateId}/extraction-rules`, `/extraction-results` |
| Index health/rebuild | `/api/v1/crates/{crateId}/index` |
| Audit | `/api/v1/crates/{crateId}/audit` |

Search modes are `lexical`, `semantic`, `hybrid` (rank fusion), and `fusion` (normalized score fusion, see [Retrieval](features/retrieval.md#fusion-retrieval)). Answers accept JSON and return server-sent events named `sources`, `delta`, `complete`, or `error`.

Each crate is additionally reachable as an OpenAI chat model: point an OpenAI client's base URL at `/api/v1/crates/{crateId}/v1` with an API key as the bearer token. The crate's configured answer model is authoritative and the request's `model` field is ignored. See [OpenAI-compatible API](integrations/openai.md) for the mapping, streaming behaviour, and client setup.

```bash
curl -H "X-API-KEY: $KEY" \
  "http://localhost:8080/api/v1/crates/$CRATE/search?q=retention&kind=chunk&mode=hybrid&limit=20"
```

## Settings

- `GET/PUT /api/v1/crates/{crateId}/settings/rag`
- `GET/PUT /api/v1/crates/{crateId}/settings/providers`

Provider responses never contain stored API keys. Submitting a blank key preserves the current secret. Changing embedding configuration schedules a versioned rebuild.

RAG settings include `retrievalStrategy` (`standard` or `proposition`) and `propositionFailurePolicy` (`fail-indexing`, `skip-chunk`, or `embed-source-chunk`). Changing either schedules a versioned rebuild. Image handling is controlled with `imageIndexingEnabled`, `imageDescriptionEnabled`, `imageVisionModelId`, `imageMaxPerRun`, `imageMaxBytes`, `imageMinSize` and `imageSkipPatterns` (see [Images](features/images.md)). `contextWindow` (0 to 5, default 0) sets the [context window](features/context-window.md) and needs no rebuild. `enrichmentEnabled` (boolean) and `enrichmentQuestions` (3 to 50, default 5) control [document enrichment](features/document-enrichment.md); changing either schedules a versioned index rebuild. The generated questions of a chunk are returned by `GET /api/v1/crates/{crateId}/documents/{documentId}/chunks/{chunkId}/questions`. `contextCompression` (boolean, default false) turns on [contextual compression](features/contextual-compression.md) and needs no rebuild. A chunk is read by its index with `GET /api/v1/crates/{crateId}/documents/{documentId}/chunks/{index}?before=0&after=0` (up to 5 neighbours on each side, joined without the chunk overlap; `404` for an unknown index). RAG evaluation has its own owner-only endpoints under `/api/v1/crates/{crateId}/evaluation`: `GET`/`PUT settings` (the judge API key is write-only), `GET summary?days=7`, `GET cases?limit=50`, `GET`/`POST golden`, `POST golden/import` (a JSON array), `DELETE golden/{id}` and `POST golden/run`. Other members and crate API keys receive `403` (see [RAG evaluation](features/evaluation.md)). Contextual chunk headers are controlled with `contextHeaderVariant` (`off`, `title`, `title-summary` or `title-summary-sections`) and `contextHeaderWords` (100 to 5,000, default 1,000); changing either schedules a versioned index rebuild (see [Contextual headers](features/contextual-headers.md)). HyDE is controlled with `hydeEnabled` (see [HyDE](features/hyde.md)); like query transformation it needs no rebuild. Query transformation is controlled with `queryTransformationEnabled` and `queryTransformationStrategies` (`rewrite`, `step-back`, `sub-queries`, or `auto`; see [Query transformation](features/query-transformation.md)); it needs no rebuild. Inspect persisted results with `GET /api/v1/crates/{crateId}/documents/{documentId}/chunks/{chunkId}/propositions`.

## Keys and portability

- `GET/POST /api/v1/me/api-keys`
- `DELETE /api/v1/me/api-keys/{id}`
- `GET/POST /api/v1/crates/{crateId}/api-keys`
- `DELETE /api/v1/crates/{crateId}/api-keys/{id}`
- `POST /api/v1/crates/{crateId}/exports`
- `POST /api/v1/crate-imports/validate`
- `POST /api/v1/crate-imports`

## Administration

Global endpoints are under `/api/v1/admin`: users, creation policy, infrastructure health, dead letters, and temporary elevations. They do not expose crate documents or search without elevation. The same operations are available in the UI at `/admin` — see [Administration](operations/administration.md).

- `GET/POST /api/v1/admin/users`
- `PUT /api/v1/admin/users/{id}/creation-entitlement` — `{"allowed": true}`
- `PUT /api/v1/admin/users/{id}/enabled` — `{"enabled": false}`
- `PUT /api/v1/admin/users/{id}/role` — `{"role": "ADMIN"}`
- `POST /api/v1/admin/users/{id}/password-reset` — `{"temporaryPassword": "..."}`
- `PUT /api/v1/admin/settings/crate-creation` — `{"mode": "ENTITLED_USERS"}`
- `PUT /api/v1/admin/settings/onboarding-policy` — `{"policy": "ADD_TO_EXISTING_CRATE", "crateId": "<crate UUID>"}`. `crateId` is required only for `ADD_TO_EXISTING_CRATE`; the other policies are `SHOW_NEW_CRATE_DIALOG` and `DO_NOTHING`.
- `PUT /api/v1/admin/settings/time-zone` — `{"timeZone": "Europe/Berlin"}`
- `GET /api/v1/admin/crates` — every crate with document, source, and member counts
- `GET /api/v1/admin/elevations` — the caller's active elevations
- `POST /api/v1/admin/crates/{crateId}/elevations` — `{"reason": "..."}`
- `DELETE /api/v1/admin/elevations/{id}`
- `GET /api/v1/admin/system`
- `GET /api/v1/admin/queue/dead-letters`
- `POST /api/v1/admin/queue/dead-letters/{id}/requeue`

## MCP

ContextCrate exposes a Model Context Protocol server so AI clients can retrieve from a crate:

- `POST /api/v1/crates/{crateId}/mcp` — crate fixed by the path
- `POST /api/v1/mcp` — crate chosen per call

Streamable HTTP through the official MCP SDK transport: `POST` for messages, `GET` for the server-to-client stream, `DELETE` to end a session, and an `Mcp-Session-Id` issued by `initialize`. Clients must send `Accept: application/json, text/event-stream`. See [MCP server](integrations/mcp.md) for the tools and client configuration.

User payloads never include the password hash. Two changes are always rejected: disabling or demoting your own account, and disabling or demoting the last enabled administrator.

Errors use the standard API exception envelope and never redirect a REST client to another crate. The previous unscoped `/api/v1/jobs`, `/search`, `/answers`, and `/backups` contracts were removed.

## System prompts

`GET` and `PUT /api/v1/crates/{crateId}/settings/system-prompt` (owners) read and set the crate's system prompt (`{"systemPrompt": "…"}`, up to 4,000 characters); the `PUT` is refused unless the administrator allowed crate system prompts with `PUT /api/v1/admin/settings/system-prompts` (`{"allowCrateSystemPrompts": true, "globalSystemPrompt": "…"}`). See [System prompts](features/system-prompts.md).

## Dedicated storage and search

`POST /api/v1/crates` accepts optional `storage` (`mode` `S3`) and `searchBackend` (`mode` `CUSTOM`) objects, which are only allowed when the administrator enabled them (`PUT /api/v1/admin/settings/backend-policy`). `POST /api/v1/crates/test-storage` and `POST /api/v1/crates/test-search-backend` test a configuration before the crate exists; `GET /api/v1/crates/{crateId}/backends` returns a crate's configuration without secrets and `POST /api/v1/crates/{crateId}/backends/test` tests it again. A refused configuration answers `422` with the failed steps. See [Dedicated storage and search per crate](features/crate-backends.md).

## Sources and ingestion jobs

Create a Git source:

```json
{
  "name": "Product repository",
  "connectorType": "GIT",
  "configuration": {
    "git": {
      "repositoryUrl": "https://git.example.com/team/product.git",
      "username": "git",
      "token": "secret"
    }
  }
}
```

The token is never returned. Source responses expose `tokenConfigured` instead. Submitting a
blank token on update preserves the existing token.

Attach any number of jobs at
`POST /api/v1/crates/{crateId}/sources/{sourceId}/ingestion-jobs`:

```json
{
  "name": "Documentation",
  "configuration": {
    "git": {
      "ref": "main",
      "includePatterns": ["docs/**", "README.md"],
      "excludePatterns": ["docs/archive/**"],
      "maxFiles": 10000,
      "maxFileBytes": 1048576,
      "output": {"chunkSize": 1000, "chunkOverlap": 200, "semantic": {"enabled": false, "method": "percentile", "value": 95, "minChars": 100}}
    }
  }
}
```

Jobs default to `"mode": "MANUAL"`. To run automatically, set `"mode": "SCHEDULED"`
and provide a five-field `cronExpression` (`minute hour day-of-month month weekday`), for example
`"*/5 * * * *"` for every five minutes, `"0 0 * * *"` for daily at midnight, or
`"0 0 * * 6,0"` for weekends. Cron expressions use the installation-wide administration time
zone. `POST .../ingestion-jobs/{jobId}/runs` remains available for both manual and scheduled jobs.

Start it with `POST .../ingestion-jobs/{jobId}/runs`. A Git run records its resolved commit in
`resolvedRevision`. Website sources use `connectorType: HTTPS`; their source configuration
owns the HTTPS URL, while each job owns crawl scope, request behavior, authentication, and output policy.

`output.semantic` turns on [semantic chunking](features/semantic-chunking.md): `method` is `percentile`, `stddev` or `iqr`, `value` the percentile or multiplier (0 = the method's default) and `minChars` the smallest chunk kept on its own. Changing it re-chunks documents on the next run.

The former crate-qualified `/jobs` endpoint is intentionally not retained.

## See also

- [Using ContextCrate](usage.md#integrations) shows where the API fits. See also [Authorization](authorization.md) for keys and [MCP](integrations/mcp.md).
