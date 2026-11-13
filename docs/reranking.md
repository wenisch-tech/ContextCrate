# Reranking

Reranking is an optional query-time stage between retrieval and presentation:

```text
BM25 / semantic / hybrid retrieval → candidate pool → reranking → search results or RAG context
```

Two kinds of reranking are available: **cross-encoder reranking** (a dedicated scoring model, local
or remote) and **LLM based reranking** (the answer model rates every candidate).

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

The model is chosen per crate through the model identifier, revision, download URL or bundle path.
For every search the pairs (question + chunk 1, question + chunk 2, …) are built from the candidate
pool and scored in **batches of 16 pairs per model run**; shorter pairs in a batch are padded and
masked. The candidates are then sorted by score, highest first, and the best ones, as many as the
crate's source limit allows, go to the response or to answer generation.

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

## LLM based reranking

Off by default. Enable **LLM based reranking** in the same Reranking section (**Settings → Model
providers → Reranking**). For every search:

1. The usual top-k candidates are retrieved (**Candidates to rerank**).
2. Each candidate chunk is sent to the crate's answer model together with the question, which is
   asked to rate the relevance from 1 (unrelated) to 10 (directly and completely answers it).
   Up to 4 candidates are rated at once; each chunk is cut at 3,000 characters.
3. The candidates are sorted by rating, highest first.
4. The best **Top results to keep** (default 4; 3 to 5 is typical, maximum 20, never more than the
   source limit) go to the response or to answer generation.

While enabled it **replaces** the cross-encoder provider, and it works even when the main
reranking switch is off. It needs a configured answer model and costs one answer-model call per
candidate on every search; the question and the candidate chunks are sent to the answer provider.
A reply without a readable number scores 0 and sorts last. If no candidate can be rated, the
original retrieval order is kept. The two settings are stored per crate and are part of crate
export and import. Metrics use the provider label `llm`.

## Failure behavior and privacy

Remote reranking receives the question and candidate chunk text. Use TLS and treat the endpoint as
a data processor. If the provider is disabled, unavailable, returns invalid data, or times out,
ContextCrate logs the failure and returns the original retrieval order, keeping search and RAG
answers available.

## See also

- [Using ContextCrate](usage.md#features) lists all features. See also [Retrieval](features/retrieval.md) and [Embeddings](embeddings.md).
