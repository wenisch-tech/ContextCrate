# Changelog

## Unreleased

- Model Context Protocol server at `/api/v1/crates/{crateId}/mcp` and `/api/v1/mcp`, so AI clients such as LiteLLM and Open WebUI can retrieve from a crate. Tools: `search_crate`, `ask_crate`, `fetch_document`, `list_documents`, `list_sources`, `list_crates`.
- API keys may now be presented as `Authorization: Bearer <token>` in addition to `X-API-KEY`.
- `/api/v1/**` now answers `401` (unauthenticated) and `403` (unauthorized) with JSON, instead of redirecting API clients to the HTML sign-in page.

- Initial standalone and distributed crawling/indexing platform.
