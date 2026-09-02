# MCP server

ContextCrate speaks the [Model Context Protocol](https://modelcontextprotocol.io), so an AI
application can retrieve from a crate on its own — the model decides when to search, and cites what
it found. LiteLLM and Open WebUI both support the Streamable HTTP transport natively.

For a plain OpenAI-compatible chat endpoint instead — fewer tools, but works with any OpenAI client
— see [OpenAI-compatible API](openai.md).

## Endpoints

| Endpoint | Crate | Use for |
| --- | --- | --- |
| `/api/v1/crates/{crateId}/mcp` | fixed by the path | One crate per client entry. Pairs with a crate-scoped API key. |
| `/api/v1/mcp` | chosen per call | One entry for several crates. Needs a personal key, or a crate-scoped key that pins the crate. |

Both endpoints speak **Streamable HTTP** through the official MCP SDK transport: `POST` for
messages, `GET` for the server-to-client stream, and `DELETE` to end a session. `initialize` returns
an `Mcp-Session-Id` which the client must send on every subsequent request. Responses come back as
`application/json` or `text/event-stream` depending on the exchange, so send

```
Accept: application/json, text/event-stream
```

as the specification requires. Protocol revisions `2024-11-05`, `2025-03-26`, `2025-06-18` and
`2025-11-25` are all accepted.

Connecting to a crate the credential cannot read fails immediately with `403`, before the handshake
completes.

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
| `ask_crate` | Ask ContextCrate's own RAG pipeline for a grounded, cited answer. Reports an error when answer generation is not configured for the crate. |
| `fetch_document` | Read one document's full text, windowed via `maxCharacters` and `offset`. |
| `list_documents` | Page through the catalogue and report the true total. |
| `list_sources` | The websites and Git repositories the crate was ingested from. |
| `list_crates` | Which crates the credential can reach. |

Every tool takes an optional `crate` argument (a UUID or the exact name). On the crate-scoped
endpoint the path wins and the argument is ignored; on the global endpoint the crate is taken from
the argument, or from a crate-scoped key, or — if exactly one crate is reachable — from that.

The tool list belongs to the server rather than to a single request, so the descriptions are
generic and do not name the crate. Call `list_crates` to see what a credential can reach.

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

`contextcrate.mcp.allowed-origins` (a comma-separated list) guards against DNS rebinding. When it
is **empty — the default — the `Origin` header is not checked**, which is what server-to-server
clients need, since they send no `Origin` at all. Set it to enforce an exact match and reject
anything else with `403`.

## Checking a deployment

The reference client is the official inspector:

```bash
npx @modelcontextprotocol/inspector
```

Choose transport "Streamable HTTP", point it at
`https://contextcrate.example.com/api/v1/crates/<crateId>/mcp`, and add an
`Authorization: Bearer cc_…` header. It should connect, list the tools by itself, and run
`search_crate` from the tool runner.
