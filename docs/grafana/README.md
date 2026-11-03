# Grafana dashboard

`contextcrate-dashboard.json` visualises ContextCrate's Prometheus metrics.

## Import

1. Make Prometheus scrape `/actuator/prometheus` (admin HTTP Basic, see [Operations → Metrics](https://contextcrate.eu/docs/operations/)).
2. In Grafana choose **Dashboards → New → Import** and upload the file.
3. Pick your Prometheus data source with the **Data source** selector at the top.

## Layout

The dashboard is grouped by topic, one row per topic, ordered from overview to detail. Each row leads with its key numbers and the most important graphs (larger), followed by supporting graphs.

| Row | Contents |
|---|---|
| Overview | Health stats: indexed documents, active crates, dead letters, MCP calls, auth failures, LLM error rate |
| MCP | Calls by tool and outcome, latency, empty/truncated searches |
| Search | Latency, passages per search, request modes |
| Embeddings | Requests and latency per provider |
| Reranking | Requests, fallbacks, latency, effect on ranking, candidates |
| LLM | Requests by purpose, latency, time to first token, provider HTTP errors |
| Answers | Grading, sources per answer, verification |
| Query transformation | Strategies used, combinations, per-strategy statistics and timing |
| HyDE | Calls per answer, failure rate, hypothetical answer length, generation time and outcomes |
| Answer timing | Stacked breakdown of where answer time goes, p50/p95 per phase |
| Context window | Neighbour chunks added per answer, windows per answer and fallbacks |
| Semantic chunking | Sentences and breakpoints per document, average threshold, fallbacks by reason |
| RAG evaluation | Faithfulness, answer relevance, completeness, Precision/Recall@K, MRR, MAP, NDCG, outcomes and judge latency |
| Ingestion & pipeline | Queue depth, throughput, stage duration, retries, ingestion runs, contextual header outcomes and timing |
| API keys | Authentication and requests per key |
| Content popularity | Most requested documents, chunks and sources |
| Runtime (collapsed) | JVM heap, HTTP server requests, CPU |

Panels marked **(selected crates)** follow the **Crate** selector (several crates or *All*); their twins marked **(all crates)** always cover the whole installation.
- **Data source** and **Crate** selectors at the top.
- Links to the [repository](https://github.com/wenisch-tech/ContextCrate) and the [documentation](https://contextcrate.eu/docs/).

Rows are collapsible, so collapse the topics you are not looking at.
