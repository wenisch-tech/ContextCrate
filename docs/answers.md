# RAG answer generation

ContextCrate can answer questions from its indexed content by combining hybrid retrieval with an OpenAI-compatible chat-completions endpoint. Embeddings retrieve relevant chunks; the answer model writes the response. These are independent model choices.

When enabled, [reranking](reranking.md) sorts a wider candidate set before ContextCrate selects
the sources included in the answer prompt.

Answer generation is disabled by default. Configure a compatible endpoint and enable it:

```bash
CONTEXTCRATE_ANSWERING_ENABLED=true
CONTEXTCRATE_ANSWERING_OPENAI_COMPATIBLE_BASE_URL=https://llm.example/v1
CONTEXTCRATE_ANSWERING_OPENAI_COMPATIBLE_MODEL=your-chat-model
CONTEXTCRATE_ANSWERING_OPENAI_COMPATIBLE_API_KEY=stored-in-a-secret
```

ContextCrate calls `POST {base-url}/chat/completions` with streaming enabled. This supports hosted and self-hosted OpenAI-compatible servers. Use TLS, inject keys through a secret store, and treat question text, client history, and retrieved source content as data sent to that provider.

## API

`POST /api/v1/crates/{crateId}/answers` is authenticated like the rest of the API and returns `text/event-stream`.

```json
{
  "question": "What does the documentation say about backups?",
  "runId": null,
  "kind": "chunk",
  "retrievalMode": "hybrid",
  "maxSources": 8,
  "history": [{"role": "user", "content": "We use PostgreSQL."}]
}
```

The stream first emits `sources`, followed by `delta` text events, then `complete`. An `error` event is emitted if a failure occurs after streaming has begun. The source list contains stable citation numbers, IDs, URL, title, chunk ordinal, snippet, and retrieval score. Generated prose is instructed to cite those sources using `[n]`; citations are model output and are not independently verified.

History is supplied by the caller for one request only. ContextCrate does not persist conversations, questions, prompts, generated answers, or source content. It limits question size, history count, source count, context budget, and output tokens through `contextcrate.answering` settings.

## OpenAI-compatible API

A crate is also reachable as an OpenAI chat model, so tools that speak the OpenAI API (Open WebUI,
LiteLLM, the OpenAI SDKs) can retrieve from it without custom integration code:

- `POST /api/v1/crates/{crateId}/v1/chat/completions`
- `GET /api/v1/crates/{crateId}/v1/models`

Point the client's OpenAI base URL at `http://your-host:8080/api/v1/crates/{crateId}/v1` and use a
ContextCrate API key as the OpenAI API key. Both endpoints authenticate like the rest of the API.

```bash
curl -s "$BASE/api/v1/crates/$CRATE/v1/chat/completions" \
  -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -d '{"model":"contextcrate","messages":[{"role":"user","content":"What about backups?"}]}'
```

The endpoint is **per crate**, one client connection per crate, because each crate has its own
answer provider, RAG policy, and index. The request's `model` field is therefore accepted but
ignored — the crate's configured answer model is authoritative and is what the response's `model`
field and `GET /models` report. Configure a different crate to use a different model, and point a
second connection at that crate.

Mapping and limitations:

- The last `user` message is the question; earlier `user` and `assistant` messages become history,
  subject to the crate's **Client-supplied conversation history** setting and the configured history
  limit.
- A client `system` message is **dropped**, not rejected. ContextCrate supplies its own system
  prompt, and honouring a client one would override the grounding and citation instructions that
  make retrieved sources untrusted data rather than instructions. Clients that attach a boilerplate
  system prompt by default therefore keep working.
- With `"stream": true` the response is a valid `chat.completion.chunk` stream terminated by
  `data: [DONE]`, but the whole answer arrives in a single content chunk rather than token by token:
  answer verification needs the complete text before anything can be sent. This is the same
  behaviour as the native SSE API above.
- Retrieval mode, source count, citations, strict grounding, and verification come from the crate's
  RAG settings; there are no OpenAI request fields for them. `temperature`, `max_tokens`, and
  `top_p` are ignored because they are crate and deployment level provider configuration here.
- `n` greater than 1, `tools`/`functions`, and a non-text `response_format` are refused with `400`
  rather than silently ignored. Errors use OpenAI's `{"error":{"message":...}}` shape.

## Grounding and safety

Retrieved pages are untrusted content. ContextCrate wraps them in explicit source delimiters and tells the model not to follow instructions contained in sources or client history. When retrieved material is insufficient, the model must begin with an evidence warning before offering general knowledge; it must not imply the sources support that general answer.

No generated answer is guaranteed correct. Treat citations as a route to inspect the underlying crawled material, particularly for consequential decisions.

## Dashboard and operations

The dashboard includes **Ask ContextCrate**, which shows retrieved sources before answer text arrives, links inline citation markers to those sources, and allows the request to be cancelled. If the provider is not configured, the UI explains that answer generation is unavailable while ordinary search remains usable.

## Runtime RAG settings

The crate-specific authenticated **Settings** page controls answer policy without editing deployment files:

- **Knowledge-base-only answers** prevents general-knowledge answers. If retrieval finds no chunks, ContextCrate returns a clear no-answer result without calling the LLM.
- **Client-supplied conversation history** can be disabled for strictly single-turn answers.
- **Inline citations** controls whether the model is instructed to write `[n]` markers; **structured sources** controls whether the `sources` SSE event is returned to clients.
- **Default retrieval mode** and **maximum sources** set the RAG policy used when callers omit request-level retrieval options. The deployment configuration remains the upper source-count limit.

These settings are stored in ContextCrate’s database and take effect on the next answer request. Endpoint URL, model, API key, context budget, and token limits remain deployment-level settings because they affect secrets and infrastructure.

The same crate page also configures its embedding provider: select local ONNX or an OpenAI-compatible endpoint, choose local model/download/cache paths, set remote model dimensions, configure answer endpoint/model, and store provider API-key overrides. Persisted settings take precedence over environment values immediately. API keys are stored in ContextCrate’s database when entered here, so protect database backups and access and enable storage-level encryption for production deployments.

Each attempt creates an `ANSWER_GENERATED` audit event containing only actor, model identifier, retrieval mode, source count, completion status, and latency. Configuration, prompts, credentials, question text, history, answer text, and source content are never written to the audit log.

For Kubernetes, configure `answering.enabled`, `answering.openaiCompatible.baseUrl`, and `answering.openaiCompatible.model`; reference a Secret containing `api-key` with `answering.openaiCompatible.apiKeySecret`.
