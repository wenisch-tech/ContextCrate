# RAG answer generation

Harvex can answer questions from its indexed content by combining hybrid retrieval with an OpenAI-compatible chat-completions endpoint. Embeddings retrieve relevant chunks; the answer model writes the response. These are independent model choices.

Answer generation is disabled by default. Configure a compatible endpoint and enable it:

```bash
HARVEX_ANSWERING_ENABLED=true
HARVEX_ANSWERING_OPENAI_COMPATIBLE_BASE_URL=https://llm.example/v1
HARVEX_ANSWERING_OPENAI_COMPATIBLE_MODEL=your-chat-model
HARVEX_ANSWERING_OPENAI_COMPATIBLE_API_KEY=stored-in-a-secret
```

Harvex calls `POST {base-url}/chat/completions` with streaming enabled. This supports hosted and self-hosted OpenAI-compatible servers. Use TLS, inject keys through a secret store, and treat question text, client history, and retrieved source content as data sent to that provider.

## API

`POST /api/v1/answers` is authenticated like the rest of the API and returns `text/event-stream`.

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

History is supplied by the caller for one request only. Harvex does not persist conversations, questions, prompts, generated answers, or source content. It limits question size, history count, source count, context budget, and output tokens through `harvex.answering` settings.

## Grounding and safety

Retrieved pages are untrusted content. Harvex wraps them in explicit source delimiters and tells the model not to follow instructions contained in sources or client history. When retrieved material is insufficient, the model must begin with an evidence warning before offering general knowledge; it must not imply the sources support that general answer.

No generated answer is guaranteed correct. Treat citations as a route to inspect the underlying crawled material, particularly for consequential decisions.

## Dashboard and operations

The dashboard includes **Ask Harvex**, which shows retrieved sources before answer text arrives, links inline citation markers to those sources, and allows the request to be cancelled. If the provider is not configured, the UI explains that answer generation is unavailable while ordinary search remains usable.

Each attempt creates an `ANSWER_GENERATED` audit event containing only actor, model identifier, retrieval mode, source count, completion status, and latency. Configuration, prompts, credentials, question text, history, answer text, and source content are never written to the audit log.

For Kubernetes, configure `answering.enabled`, `answering.openaiCompatible.baseUrl`, and `answering.openaiCompatible.model`; reference a Secret containing `api-key` with `answering.openaiCompatible.apiKeySecret`.
