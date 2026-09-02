# OpenAI-compatible API

A crate is reachable as an OpenAI chat model, so any OpenAI-client-compatible tool — Open WebUI,
LiteLLM, the OpenAI SDKs, or a plain `curl` — can retrieve from it with only a base URL and an API
key, no custom integration code.

## Endpoint is per crate

Each crate has its own answer provider (endpoint, model, API key), its own RAG policy, and its own
index, so the crate — not the request's `model` field — is the unit of configuration. Point one
client "connection" at each crate's own base URL:

```
http://contextcrate.example.com/api/v1/crates/<crateId>/v1
```

| Endpoint | Purpose |
| --- | --- |
| `POST /api/v1/crates/{crateId}/v1/chat/completions` | Ask a question, streaming or not. |
| `GET /api/v1/crates/{crateId}/v1/models` | Reports that crate's one configured answer model. |

There is no global endpoint that selects a crate by name — that would collide with the crate's own
model setting. Configure a separate client entry per crate.

## Authentication

Use the same bearer token as the rest of the API:

```
Authorization: Bearer cc_your-token
```

Create a **crate-scoped** key for the client — it can reach exactly one crate and nothing else:

```bash
curl -X POST https://contextcrate.example.com/api/v1/crates/$CRATE/api-keys \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"open-webui","role":"VIEWER"}'
```

The token is shown once. An unauthenticated request gets `401` with `WWW-Authenticate`; a request
for a crate the key cannot reach gets `403`. Errors use OpenAI's shape:
`{"error":{"message":...,"type":...}}`.

## Requesting an answer

```bash
curl -s https://contextcrate.example.com/api/v1/crates/$CRATE/v1/chat/completions \
  -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -d '{
    "model": "contextcrate",
    "messages": [{"role": "user", "content": "What does the documentation say about backups?"}]
  }'
```

```json
{
  "id": "chatcmpl-...",
  "object": "chat.completion",
  "created": 1730000000,
  "model": "your-configured-model",
  "choices": [{"index": 0, "message": {"role": "assistant", "content": "..."}, "finish_reason": "stop"}],
  "usage": {"prompt_tokens": 120, "completion_tokens": 40, "total_tokens": 160}
}
```

`"model"` in the **request** is accepted but ignored — send whatever your client defaults to. The
crate's configured answer model is authoritative, and is what the **response's** `model` field and
`GET /models` report, so you can confirm which model actually ran.

Set `"stream": true` for `text/event-stream` output. ContextCrate's answer verification needs the
complete answer before it can decide whether to release it, so the response is one role chunk, one
content chunk carrying the whole answer, a finish chunk, then `data: [DONE]` — valid
`chat.completion.chunk` framing, but no token-by-token typewriter effect.

## Citations

When inline citations are on (**Settings → RAG → Inline citations**), the model writes markers like
`[1]` into the answer. The OpenAI response schema has no field for a source list, so the citation
has to travel inside the message text. Rather than inlining a link at every mention — which reads as
cluttered — ContextCrate turns each marker whose number matches a retrieved source into a
superscript and appends a reference list after a horizontal rule:

```
Backups run nightly¹ and are retained for 30 days².

---

[1] [Backup policy](https://docs.example.com/backups)
[2] [Retention policy](https://docs.example.com/retention-policy)
```

The markers are **real superscript characters** (`¹`, `²`, … — Unicode, not `<sup>` markup). The
response is a plain string and how it is displayed is entirely the client's choice, so markup would
only render where a client happens to support it: an HTML tag that a client strips or does not
interpret would leave literal `<sup>[1]</sup>` noise in the text, which is worse than no formatting
at all. A superscript character displays as a superscript everywhere, including in clients that
render nothing. The reference list still uses Markdown links, where the failure mode is benign — a
client that does not render Markdown simply shows the title and URL as readable text.

Reference list entries keep the citation's own number rather than renumbering sequentially, so a
marker in the body always matches the same bracketed number below it — even when, say, source `[2]`
was retrieved but not cited and only `[1]` and `[3]` appear in the list.

The link always comes from ContextCrate's own retrieved-source URI, never from anything the model
wrote, so a citation cannot point somewhere the model made up. A marker that doesn't match any
retrieved source, or whose source has no plain `http(s)` URL to link to, is left as plain `[n]` text
and does not appear in the reference list — there is nothing accurate to point it at.

## Conversation mapping

- The **last `user` message** becomes the question. Earlier `user`/`assistant` messages become
  conversation history, subject to the crate's **Client-supplied conversation history** setting and
  its configured history limit.
- A **`system` message is dropped**, not rejected. ContextCrate supplies its own system prompt and
  treats retrieved sources and history as untrusted data, never instructions; honouring a client
  system message would undo that. Clients that always attach a boilerplate system prompt keep
  working without configuration.
- Retrieval mode, source count, citations, strict grounding, and answer verification come from the
  crate's RAG settings (**Settings → RAG** in the UI) — there is no OpenAI request field for them.
  `temperature`, `max_tokens`, and `top_p` are accepted but ignored; they are the crate's
  deployment-level provider configuration, not per-request knobs.
- `n` greater than `1`, `tools`/`functions`, and a non-default `response_format` are refused with
  `400` rather than silently ignored, since ContextCrate cannot honour them.

## Open WebUI

Add an **OpenAI API** connection with the base URL above and the crate-scoped key as the API key.
The crate's model appears in the model picker via `GET /models`.

## LiteLLM

```yaml
model_list:
  - model_name: contextcrate-product-docs
    litellm_params:
      model: openai/contextcrate
      api_base: "https://contextcrate.example.com/api/v1/crates/<crateId>/v1"
      api_key: "cc_your-token"
```

Add one `model_list` entry per crate, each with its own crate-scoped key.

## Checking a deployment

```bash
curl -s https://contextcrate.example.com/api/v1/crates/$CRATE/v1/models \
  -H "Authorization: Bearer $KEY"
```

should return the crate's configured model, or an empty `data` list if answering is not yet
configured for that crate (see [RAG answer generation](../answers.md)).

## Related

- [RAG answer generation](../answers.md) — how answers are prepared, grounding and safety, the
  native streaming API this endpoint wraps.
- [MCP server](mcp.md) — an alternative integration for AI applications that speak MCP instead of
  the OpenAI API, with more tools (search, document fetch, listing) than a chat endpoint exposes.
