# Embeddings and hybrid retrieval

Harvex retrieves source documents and chunks. It does not generate answers yet: a future LLM receives the retrieved chunks and the user's question. The embedding model used for retrieval and that future LLM are independent choices.

## Retrieval modes

`lexical` is the existing BM25-style keyword retrieval. `semantic` embeds the question and finds nearby vectors. `hybrid` runs both and combines their ranked lists with reciprocal-rank fusion (RRF), which avoids treating BM25 and vector scores as comparable. The deployment default is `hybrid` when embeddings are healthy; clients can override it with `GET /api/v1/search?q=...&mode=lexical|semantic|hybrid`.

Responses include the selected mode, final score, and lexical/semantic component scores. `POST /api/v1/index/rebuild` recreates the derived v2 vector index from canonical documents and chunks, without crawling again.

## Local default

The default provider is `local`. On first indexing or semantic query it downloads the pinned `Xenova/multilingual-e5-small` ONNX bundle into `data/models`. It is a multilingual, CPU-friendly model and produces 384-dimensional normalized vectors. The first download needs network access and disk space; later starts reuse the cache. CPU inference is appropriate for development and modest indexing workloads, but bulk rebuilds can take time.

Set `HARVEX_EMBEDDINGS_LOCAL_CACHE_PATH` to persistent storage. For air-gapped deployments, copy a compatible bundle into a directory containing `tokenizer.json`, `config.json`, and `onnx/model_quantized.onnx` (or `onnx/model.onnx`), then set `HARVEX_EMBEDDINGS_LOCAL_MODEL_PATH`. This explicit path takes precedence over downloading.

Changing model, revision, dimension, or normalization requires a new vector generation and a full `POST /api/v1/index/rebuild`. Do not reuse vectors from a different model. Keep the previous index/cache until validation is complete so rollback consists of restoring the earlier configuration and rebuilding it.

## OpenAI-compatible endpoints

Set `HARVEX_EMBEDDINGS_PROVIDER=openai-compatible` and configure:

```bash
HARVEX_EMBEDDINGS_OPENAI_COMPATIBLE_BASE_URL=https://embedding.example/v1
HARVEX_EMBEDDINGS_OPENAI_COMPATIBLE_MODEL=text-embedding-model
HARVEX_EMBEDDINGS_OPENAI_COMPATIBLE_DIMENSIONS=1536
HARVEX_EMBEDDINGS_OPENAI_COMPATIBLE_API_KEY=stored-in-a-secret
```

### Endpoint URL

The endpoint setting is an **API base URL**. Harvex removes a trailing slash and appends
`/embeddings`, then sends an OpenAI-format `POST` request:

```text
{base-url}/embeddings
```

Include any version prefix required by the provider, but do not include `/embeddings`
itself. For example:

| Provider | Base URL to configure | Request sent by Harvex |
| --- | --- | --- |
| OpenAI | `https://api.openai.com/v1` | `POST https://api.openai.com/v1/embeddings` |
| Ollama OpenAI-compatible API | `http://localhost:11434/v1` | `POST http://localhost:11434/v1/embeddings` |
| Compatible gateway | `https://embedding.example/v1` | `POST https://embedding.example/v1/embeddings` |

For example, configuring `http://localhost:11434` for Ollama omits its required `/v1`
prefix and makes Harvex call `POST http://localhost:11434/embeddings`, which returns
HTTP 404. Conversely, configuring a URL that already ends in `/embeddings` makes the
request end in `/embeddings/embeddings`.

The configured model must be an embedding model supported by that endpoint; it does
not need to be the same model used to generate answers. Harvex expects an OpenAI-style
response containing one vector for every input text. The configured dimensions must
match the returned vector size.

The API key, when configured, is sent as a Bearer credential and must be injected from
a secret store, never job JSON or version control. The text being embedded is sent to
the endpoint, so use TLS and choose an endpoint consistent with your data-boundary
policy.

## Docker and Helm

The image exposes `/app/data/models` for the downloaded cache and `/models` for a mounted offline bundle. In Compose, persist `/app/data`; set provider and endpoint variables in the environment. In Helm, `embeddings.local.cachePath` defaults to `/app/data/models`; set `embeddings.local.modelPathMount` to a PVC name for an offline bundle. Use `embeddings.openaiCompatible.apiKeySecret` for a Kubernetes Secret whose `api-key` key contains the credential.

## Operations and troubleshooting

Lexical retrieval remains available if embeddings are disabled (`HARVEX_EMBEDDINGS_ENABLED=false`) or unavailable. Semantic/hybrid requests fail clearly when no embedding provider is usable. Confirm the model directory is readable by UID 65532, that the ONNX/tokenizer files are complete, and that the configured endpoint returns vectors matching the configured dimension.

If a semantic search or an answer request fails with `Embedding endpoint returned HTTP 404`, check the base URL first. It normally means that the configured server does not expose `{base-url}/embeddings`—most commonly because a required version prefix such as `/v1` is missing, or because `/embeddings` was included in the configured base URL. Correct the URL, verify the model is available from that endpoint, then rebuild the index with `POST /api/v1/index/rebuild`. A rebuild is required because existing documents were indexed without vectors or with vectors from the previous model.

The index health detail reports backend and embedding availability, but never credentials or source text. Search indices are derived data and are rebuilt after restore or model changes; backup bundles do not copy index internals.
