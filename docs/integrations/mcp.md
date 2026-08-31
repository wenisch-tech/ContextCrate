# MCP server

ContextCrate speaks the [Model Context Protocol](https://modelcontextprotocol.io), so an AI
application can retrieve from a crate on its own — the model decides when to search, and cites what
it found. LiteLLM and Open WebUI both support the Streamable HTTP transport natively.

## Endpoints

| Endpoint | Crate | Use for |
| --- | --- | --- |
| `POST /api/v1/crates/{crateId}/mcp` | fixed by the path | One crate per client entry. Pairs with a crate-scoped API key. |
| `POST /api/v1/mcp` | chosen per call | One entry for several crates. Needs a personal key, or a crate-scoped key that pins the crate. |

The server is stateless. Every request is answered with a single JSON object; `GET` and `DELETE`
return `405`, and no `Mcp-Session-Id` is issued. Protocol revisions `2025-11-25`, `2025-06-18` and
`2025-03-26` are accepted.

## Authentication

Both header forms carry the same `cc_…` API key:

```
Authorization: Bearer cc_your-token
X-API-KEY: cc_your-token
```

Create a **crate-scoped** key for an AI client — it can reach exactly one crate and nothing else,
and cannot be an owner:

```bash
curl -X POST https://contextcrate.example.com/api/v1/crates/$CRATE/api-keys \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"litellm","role":"VIEWER"}'
```

The token is shown once. An unauthenticated request gets `401` with `WWW-Authenticate`; a request
for a crate the key cannot reach gets `403`.

## Tools

| Tool | Purpose |
| --- | --- |
| `search_crate` | Retrieve passages matching a query, in full, with title, source URI and score. |
| `ask_crate` | Ask ContextCrate's own RAG pipeline for a grounded, cited answer. Advertised only when answer generation is configured for the crate. |
| `fetch_document` | Read one document's full text, windowed via `maxCharacters` and `offset`. |
| `list_documents` | Page through the catalogue and report the true total. |
| `list_sources` | The websites and Git repositories the crate was ingested from. |
| `list_crates` | Which crates the credential can reach. Global endpoint only. |

Tool descriptions are built per request and carry the crate's own name and description, so the model
can tell which knowledge base it is talking to. On the crate-scoped endpoint there is no `crate`
argument; on the global endpoint every tool accepts one (a UUID or the exact name).

`search_crate` returns the **complete chunk text**, not the short snippet the REST search endpoint
exposes. Results are capped at 25 passages and a total of roughly 40 000 characters.

## LiteLLM

```yaml
mcp_servers:
  contextcrate_product_docs:
    url: "https://contextcrate.example.com/api/v1/crates/<crateId>/mcp"
    transport: "http"
    auth_type: "api_key"
    auth_value: "cc_your-token"
    description: "Product documentation knowledge base"
```

`auth_type: api_key` makes LiteLLM send `X-API-Key`, which ContextCrate accepts. Add one entry per
crate, each with its own crate-scoped key.

## Open WebUI

Add an MCP server (Streamable HTTP, v0.6.31 or newer) pointing at the same URL and authenticate with
a bearer token. Open WebUI supports only Streamable HTTP, which is the transport implemented here.

## Limits

**Retrieval is ranked by relevance, not exhaustive.** `search_crate` returns the best passages, not
every match, and reports as much in its output. For "what is in this knowledge base" use
`list_documents`, which pages through everything and reports the real total. `ask_crate` does not
help with completeness — it retrieves through the same ranked search, capped by the crate's source
limit.

For a genuinely complete list of some entity across a whole crate — every IP address, every image
reference — the right tool is ContextCrate's [extraction rules](../api.md), which run over every
chunk at ingest time. Extraction is not exposed over MCP today.

**ContextCrate is a text-only corpus.** No images are stored. The Git connector accepts only `.md`,
`.markdown` and `.txt`; the crawler follows `a[href]` and never `img[src]`; and the HTML parser keeps
text only, so `<img>` disappears entirely. In Markdown, `![Architecture diagram](arch.png)` is
reduced to the bare words `Architecture diagram`, indistinguishable from prose. A model asked which
images a crate contains will find that text and answer confidently and wrongly, so tell it — or your
users — that images are not ingested.

**No CORS and no rate limiting.** Server-to-server clients such as LiteLLM and Open WebUI are
unaffected, but a browser page calling the endpoint directly from JavaScript is blocked. Put an
authenticating reverse proxy in front of a publicly reachable deployment.

An `Origin` header is rejected with `403` unless it is listed in `contextcrate.mcp.allowed-origins`
(a comma-separated list, empty by default), as the specification requires against DNS rebinding.
