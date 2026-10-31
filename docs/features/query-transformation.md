# Query transformation

Query transformation reshapes the user's question *before retrieval* so the right passages are
found for vague, over-specific or multi-part questions. It is switched on **per crate** (default:
off) and works with three strategies plus an **Auto** mode.

## What it does — and what it does not

- It runs only when an **answer** is prepared: the dashboard chat, `POST …/answers` (SSE), the
  OpenAI-compatible endpoint and the MCP tool `ask_crate`. Plain search (`search_crate`, the search
  API, the search page) is not transformed.
- Only **retrieval** sees the transformed queries. The question the user typed is still what the
  generation model answers, what chunk grading judges relevance against, and what answer
  verification checks. The model therefore receives the *original question plus all retrieved
  chunks*.
- It needs an enabled and configured answer model, because the transformation itself is done by
  that model. If the answer model is unavailable the feature is skipped and the question is searched
  as usual.

## Strategies

| Strategy | The model is asked to … | Queries that are searched | Use it when |
| --- | --- | --- | --- |
| `rewrite` — query rewriting | Rewrite the question as one precise, self-contained query that states details the original only implied, using the last (up to 4) chat messages to resolve references like “it”. | Only the rewritten query. | The question is vague, very short, or a chat follow-up. |
| `step-back` — step-back prompting | Write one broader, more general question about the underlying concept. | The question **and** the broader question. | The question is very specific (a version, an error code, one parameter) and the answer needs background documentation. |
| `sub-queries` — sub-query composition | Split the question into 2–4 independent, self-contained sub-questions (one line each). One-aspect questions come back as a single line. | Each sub-query; the question itself is not searched. | The question has several parts, e.g. “How do I install X **and** configure Y?”. |

Example (`rewrite`): *“backup how”* → *“How do I back up the PostgreSQL database and the artifact
store of a ContextCrate installation?”* Example (`sub-queries`): *“Install X and configure Y”* →
*“How do I install X?”*, *“How do I configure Y?”*

All prompts treat the question and conversation as **untrusted data, never instructions**, ask for
the question's language to be kept, and demand a bare answer (one line, or one sub-question per
line). Surrounding quotes and list markers are stripped from the replies.

## Combining strategies

Select several strategies and they always run in the fixed order **rewrite → step-back →
sub-queries**, regardless of how they were selected. They form a pipeline: each step works on the
result of the previous one.

| Selected | Searched queries |
| --- | --- |
| rewrite | rewritten |
| step-back | question, general |
| sub-queries | sub₁ … subₙ |
| rewrite + step-back | rewritten, general (general is derived from the rewritten query) |
| rewrite + sub-queries | rewritten, sub₁ … subₙ (sub-queries are derived from the rewritten query) |
| step-back + sub-queries | question, general, sub₁ … subₙ |
| all three | rewritten, general, sub₁ … subₙ |

Queries are de-duplicated (case-insensitive), each is cut to 500 characters, and at most **6**
queries are searched.

## Auto mode

With **Auto**, one extra model call decides which strategies to apply, following this guidance:
vague / short / context-dependent → `rewrite`; very specific → `step-back`; several parts → 
`sub-queries`; already clear → none. The model must answer with JSON, `{"strategies":["rewrite", …]}`.
The reply is parsed leniently (JSON may be surrounded by prose), unknown names are ignored, and
the chosen strategies then run through the same fixed-order pipeline above. If the reply cannot be
parsed, or no strategy is chosen, the question is searched unchanged.

## Retrieval and merging

Each query is searched with the same retrieval mode, source limit and filters as a normal answer
(hybrid/semantic/lexical, and reranking if enabled). The ranked result lists are merged with
**Reciprocal Rank Fusion** using the configured RRF constant: a chunk found by several queries
ranks higher than a chunk found by one. Chunks are de-duplicated by id and the list is cut to the
crate's **maximum sources per answer**. Two consequences worth knowing:

- Reranking runs *per query*, so the reranker sees the transformed query, not the original question.
- Grading (if enabled) and verification use the original question.

## Failure behavior

Every step is fail-open. If a model call fails or returns nothing usable, that step is skipped and
the pipeline continues with what it has; if nothing worked, the original question is searched. An
answer is never blocked because of query transformation.

## Cost and latency

| Setting | Extra model calls before retrieval | Searches |
| --- | --- | --- |
| off | 0 | 1 |
| rewrite | 1 | 1 |
| step-back | 1 | 2 |
| sub-queries | 1 | 2–4 |
| all three | 3 | up to 6 |
| auto | 1 + one per chosen strategy | depends on the choice |

These calls run before the answer starts streaming, so they add directly to time-to-first-token.
The question (and up to four recent chat messages for `rewrite` and Auto) is sent to the
crate's answer endpoint; nothing is written to audit logs except the strategy names.

## Configuration

*Crate settings → Retrieval & answers → Query transformation*: a master switch and the strategy
toggles (Auto excludes the three fixed ones). Changing it needs no index rebuild.

REST: `PUT /api/v1/crates/{crateId}/settings/rag` accepts `queryTransformationEnabled` (boolean)
and `queryTransformationStrategies` (array of `rewrite`, `step-back`, `sub-queries`, or `["auto"]`);
omitted fields keep their current value. `GET` returns `queryTransformationEnabled` and
`queryTransformationStrategies` as a comma-separated canonical string such as
`rewrite,sub-queries`, or `auto`. Defaults: off, `auto`. The settings are part of crate export and
restore; bundles from older versions restore with the feature off.

## Observability

- `contextcrate_answer_query_transformation_total{crate,strategy,outcome}` — `outcome` is `ok`,
  `failed` or `skipped` (`strategy="none"` + `skipped` means the feature was on but the answer model
  was unavailable); `auto` is reported as its own strategy for the classification call.
- `contextcrate_answer_query_transformation_queries` — queries searched per question (a count near 1
  means the transformation rarely changes anything).
- `contextcrate_llm_requests_total{purpose="transform"}` and `llm_duration_seconds{purpose="transform"}`
  — the extra model calls.
- The `ANSWER_GENERATED` audit entry contains `transform=rewrite+sub-queries` (or `none`).
- The Grafana dashboard's crate section has a *Query transformation* panel.

## Recommendations

- **Start with Auto.** It spends one cheap call to avoid paying for strategies a question does not
  need. Switch to fixed strategies once you know your traffic.
- **Chat front-ends (Open WebUI, LiteLLM, MCP agents):** enable **rewrite** — follow-ups like “and
  restores?” only make sense with the conversation, and rewrite is the strategy that uses it.
- **Narrow technical documentation** (versions, error codes, config keys): add **step-back**, so the
  conceptual page is retrieved next to the exact one.
- **FAQ / comparison / “how do I A and B” traffic:** use **sub-queries**.
- **Do not enable all three by default.** Three extra calls and up to six searches add noticeable
  latency, and the broader queries can dilute the top results for simple questions.
- Pair it with **reranking** and a **higher “maximum sources per answer”** (6–10): fused results
  from several queries need room, and the reranker restores precision.
- Use a **fast, inexpensive answer model**; the transformation calls are on the critical path.
- **Measure it**: compare the grading kept-ratio (`answer_grading_kept_ratio`), the share of empty
  searches and the verification statuses before and after enabling it on a crate.
