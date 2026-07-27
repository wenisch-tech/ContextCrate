# Reranking

Reranking is an optional query-time stage between retrieval and presentation:

```text
BM25 / semantic / hybrid retrieval → candidate pool → cross-encoder reranking → search results or RAG context
```

The first stage is fast and broad. The reranker evaluates the question together with every
candidate chunk and gives the most relevant chunks to the search response or answer model. It
does not modify indexed data, so changing a reranker never requires an index rebuild.

## Configuration

Reranking is disabled by default. Every crate owner can configure it in **Settings → Model
providers → Reranking**. The page exposes activation, provider, candidate count, all model or
endpoint fields, limits, timeout, and a local-model download/validation action. Crate settings
override deployment configuration. Blank API-key fields preserve the stored secret and secrets
are never returned by the API.

The candidate count is the number of initial results that are rescored; it must be at least as
large as the requested result count. Higher values can improve relevance but increase latency and
the amount of text sent to a remote provider.

## Local ONNX cross-encoder

Select **Local ONNX cross-encoder**, configure a Hugging Face model identifier, pinned revision
and download URL, then download it from the settings page. For offline operation, provide a bundle
path instead. The bundle must contain `tokenizer.json` and either
`onnx/model_quantized.onnx` or `onnx/model.onnx`, and must be a sequence-classification
cross-encoder whose `logits` output is a relevance score. Inputs are truncated to 512 tokens.

`CONTEXTCRATE_RERANKING_LOCAL_MODEL_ID`, `..._REVISION`, `..._DOWNLOAD_URL`,
`..._CACHE_PATH`, and `..._MODEL_PATH` provide deployment defaults.

## Cohere-compatible endpoint

Select **Cohere-compatible rerank endpoint** and configure its base URL, model, API key, maximum
characters per candidate, and timeout. ContextCrate appends `/v2/rerank` and sends the Cohere v2
shape with `model`, `query`, `documents`, and `top_n`; results must contain each document's
`index` and `relevance_score`.

Deployment defaults are `CONTEXTCRATE_RERANKING_ENABLED`, `..._PROVIDER`,
`..._CANDIDATE_LIMIT`, `CONTEXTCRATE_RERANKING_COHERE_COMPATIBLE_BASE_URL`, `..._MODEL`,
`..._API_KEY`, `..._MAX_INPUT_CHARACTERS`, and `..._TIMEOUT_SECONDS`.

Remote reranking receives the question and candidate chunk text. Use TLS and treat the endpoint as
a data processor. If the provider is disabled, unavailable, returns invalid data, or times out,
ContextCrate logs the failure and returns the original retrieval order, keeping search and RAG
answers available.
