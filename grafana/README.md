# Grafana dashboard

`contextcrate-dashboard.json` visualises ContextCrate's Prometheus metrics.

## Import

1. Make Prometheus scrape `/actuator/prometheus` (admin HTTP Basic, see [Operations → Metrics](https://contextcrate.eu/docs/operations/)).
2. In Grafana choose **Dashboards → New → Import** and upload the file.
3. Pick your Prometheus data source with the **Data source** selector at the top.

## Layout

- **Data source** and **Crate** selectors at the top (the crate selector supports several crates or *All*).
- **Global** section: queue depth, pipeline throughput and latency, API key authentication, MCP, search, embeddings, reranking, LLM, answer verification, JVM and HTTP.
- **Crate** section (follows the Crate selector): the most requested documents, chunks and sources, MCP usage and empty-result rate, API key usage per key, ingestion, embeddings, reranker fallbacks and effect, LLM latency, time to first token and grading/verification behaviour.
- Links to the [repository](https://github.com/wenisch-tech/ContextCrate) and the [documentation](https://contextcrate.eu/docs/).

Grafana dashboards have no native tabs before Grafana 12, so the two sections are collapsible rows; collapse one to work in the other.
