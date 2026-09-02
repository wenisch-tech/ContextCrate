# Changelog

## Unreleased

- Each crate is now reachable as an OpenAI-compatible chat model at `POST /api/v1/crates/{crateId}/v1/chat/completions` (plus `GET .../v1/models`), so Open WebUI, LiteLLM, and the OpenAI SDKs can retrieve from a crate by setting a base URL and an API key. The endpoint is per crate because each crate has its own answer model, RAG policy, and index; the request's `model` field is ignored and the crate's configured model is reported back. Streaming returns a valid `chat.completion.chunk` stream, though the answer arrives as one content chunk because verification needs the complete text. Known inline citation markers (`[1]`) become Unicode superscript, with a reference list of the cited sources appended after a horizontal rule, since the OpenAI schema has no separate field for a source list.
- Global `CONTEXTCRATE_TLS_TRUST_ALL_CERTIFICATES` flag (Helm: `tls.trustAllCertificates`) disables TLS certificate and hostname validation process-wide — model providers, OIDC, Git/web-crawler ingestion, robots.txt, Keycloak, MCP, S3, RabbitMQ, and Postgres — instead of requiring a separate flag per integration. Existing per-job and OIDC trust-all flags keep working and are OR'd in.
- Model Context Protocol server at `/api/v1/crates/{crateId}/mcp` and `/api/v1/mcp`, so AI clients such as LiteLLM and Open WebUI can retrieve from a crate. Tools: `search_crate`, `ask_crate`, `fetch_document`, `list_documents`, `list_sources`, `list_crates`.
- The MCP endpoints now use the official SDK's Streamable HTTP transport (`org.springframework.ai:mcp-spring-webmvc`), which adds `GET` and `DELETE`, SSE responses, `Mcp-Session-Id` sessions and protocol revisions back to `2024-11-05`. The previous hand-written transport answered `POST` with JSON only and refused `GET`, which real clients could not connect to.
- API keys are also authenticated on async dispatches, without which a streaming response was rejected part-way through by the authorization filter.
- `contextcrate.mcp.allowed-origins` now means "do not check" when empty, instead of rejecting every request that carried an `Origin` header.
- API keys may now be presented as `Authorization: Bearer <token>` in addition to `X-API-KEY`.
- `/api/v1/**` now answers `401` (unauthenticated) and `403` (unauthorized) with JSON, instead of redirecting API clients to the HTML sign-in page.
- Initial standalone and distributed crawling/indexing platform.
