# Operations

## Health boundaries

Installation administrators can inspect global backend and queue health under `/api/v1/admin/system`. Crate members inspect index health and generations under `/api/v1/crates/{crateId}/index`.

Failures are intentionally contained:

- An embedding or answer-provider failure affects only calls for the configured crate.
- A failed generation never replaces the active index.
- A purge failure leaves a `PURGE_FAILED` tombstone and can be retried.
- Dead-lettered work retains its crate identity.

## Metrics

ContextCrate exposes Prometheus metrics at `/actuator/prometheus`. All business metrics use the `contextcrate.*` prefix (`contextcrate_*` in Prometheus). Metrics that belong to a crate carry a `crate` tag with the **crate name**; installation-wide metrics have no `crate` tag.

### Scraping

The endpoint requires an administrator (it exposes crate names and document titles across all crates). Use HTTP Basic with an admin account:

```yaml
scrape_configs:
  - job_name: contextcrate
    metrics_path: /actuator/prometheus
    basic_auth:
      username: admin@example.com
      password_file: /etc/prometheus/contextcrate-password
    static_configs:
      - targets: ["contextcrate:8080"]
```

### Global metrics

| Metric | Type | Tags | Meaning |
| --- | --- | --- | --- |
| `contextcrate_pipeline_queue_depth` | gauge | `stage` | Pending work items per stage |
| `contextcrate_pipeline_dead_letters` | gauge | | Work that exhausted its retries |
| `contextcrate_index_documents` | gauge | | Current document versions |
| `contextcrate_crates` | gauge | `status` | Crates by lifecycle status |
| `contextcrate_apikey_auth_total` | counter | `result` (`ok`, `invalid`, `revoked`), `key_type` | API key authentication outcomes |
| `contextcrate_apikey_created_total`, `contextcrate_apikey_revoked_total` | counter | `key_type` | Key lifecycle |

### Per-crate metrics

All carry `crate`. Timers are published as histograms (`*_seconds_bucket`) so quantiles can be computed.

| Area | Metrics |
| --- | --- |
| API keys | `apikey_requests_total{key,key_type}` |
| Pipeline | `pipeline_completed_total`, `pipeline_failed_total`, `pipeline_retries_total`, `pipeline_dead_lettered_total`, `pipeline_duration_seconds` (all `stage`) |
| Ingestion | `ingestion_runs_started_total{connector}`, `ingestion_runs_total{status}`, `ingestion_run_duration_seconds{status}`, `ingestion_documents_total{result=new\|updated\|unchanged}`, `ingestion_documents_indexed_total`, `ingestion_context_header_total{variant,outcome}`, `ingestion_context_header_duration_seconds`, `ingestion_context_header_length` (see [Contextual headers](features/contextual-headers.md#observability)), `ingestion_chunks_per_document`, `ingestion_document_characters`, `ingestion_index_duration_seconds`, `ingestion_embedding_rechunks_total`, `ingestion_semantic_sentences`, `ingestion_semantic_breakpoints`, `ingestion_semantic_threshold`, `ingestion_semantic_duration_seconds`, `ingestion_semantic_fallback_total{reason}` (see [Semantic chunking](features/semantic-chunking.md#where-to-see-it)) |
| MCP | `mcp_tool_calls_total{tool,outcome}`, `mcp_tool_duration_seconds{tool}`, `mcp_search_hits{mode}`, `mcp_search_empty_total`, `mcp_search_truncated_total`, `mcp_fetch_not_found_total` |
| Search | `search_requests_total{backend,mode,kind,outcome}`, `search_duration_seconds`, `search_hits` |
| Embeddings | `embedding_requests_total{provider,op,outcome}`, `embedding_duration_seconds`, `embedding_batch_size` |
| Reranking | `rerank_requests_total{provider,outcome}`, `rerank_duration_seconds`, `rerank_candidates`, `rerank_fallbacks_total`, `rerank_original_top_hit_position` |
| LLM | `llm_requests_total{purpose,outcome}`, `llm_duration_seconds{purpose}`, `llm_time_to_first_token_seconds`, `llm_http_errors_total{status}` |
| Answers | `answer_requests_total{transformed}`, `answer_prepare_duration_seconds`, `answer_retrieval_duration_seconds`, `answer_generation_duration_seconds`, `answer_duration_seconds`, `answer_query_transformation_total{strategy,outcome}`, `answer_query_transformation_selection_total{selection,applied}`, `answer_query_transformation_duration_seconds{strategy}`, `answer_query_transformation_overhead_seconds`, `answer_query_transformation_generated{strategy}`, `answer_query_transformation_queries` (details in [Query transformation](features/query-transformation.md#observability)), `answer_hyde_total{outcome,reason}`, `answer_hyde_duration_seconds`, `answer_hyde_length` (see [HyDE](features/hyde.md#observability)), `answer_context_window_added`, `answer_context_window_windows`, `answer_context_window_fallback_total{reason}` (see [Context window](features/context-window.md#observability)), `answer_verification_total{status}`, `evaluation_cases_total{outcome,source}`, `evaluation_context_*`, `evaluation_faithfulness`, `evaluation_answer_relevance`, `evaluation_completeness`, `evaluation_duration_seconds`, `evaluation_judge_failures_total{dimension}` (see [RAG evaluation](features/evaluation.md#metrics-and-grafana)), `answer_sources`, `answer_grading_kept_ratio` |

`purpose` is `grade`, `answer`, `verify`, `revise`, `judge` (RAG evaluation), `context_header` (document titles for [contextual chunk headers](features/contextual-headers.md#observability)), `hyde` (hypothetical answer, see [HyDE](features/hyde.md)) or `transform` (query transformation, see [Query transformation](features/query-transformation.md#observability)). `rerank_original_top_hit_position` is the position the reranker gave the hit that retrieval ranked first (0 means it agreed).

### Most requested documents, chunks and sources

Every document and chunk returned by `search_crate`, `ask_crate` (and the REST and OpenAI-compatible answer endpoints) and every `fetch_document` call is counted. Counts are kept in memory, flushed to the `content_hit` table every 15 seconds, and survive restarts. To avoid unbounded label cardinality only the top *N* per crate (default 20) are published as gauges:

- `contextcrate_content_document_hits{crate,document_id,title,uri}`
- `contextcrate_content_chunk_hits{crate,chunk_id,title,ordinal}`
- `contextcrate_content_source_hits{crate,source_id,source}`

These are cumulative counts; series disappear when they drop out of the top *N*.

### Cardinality

The `crate` label is the crate name, so cardinality grows with the number of crates (fine for tens or hundreds). API key names are free text: at most `contextcrate.metrics.key-tag-limit` (default 100) distinct names are used and further keys are reported as `other`. See [Configuration](configuration.md#metrics) for the settings.

### Grafana dashboard

Import `grafana/contextcrate-dashboard.json` (Dashboards → New → Import). The dashboard has a **Data source** selector and a **Crate** selector at the top, links to the repository and to the documentation, and two sections: **Global** (installation-wide) and **Crate** (filtered by the selected crates, including the most requested documents, chunks and sources, reranker behaviour and LLM latency).

## Index rebuild runbook

1. Verify the crate is `ACTIVE`.
2. Check provider credentials and model availability.
3. Start `POST /api/v1/crates/{crateId}/index/rebuild`.
4. Poll `GET /api/v1/crates/{crateId}/index`.
5. Confirm the new generation becomes `ACTIVE` and the document count matches expectations.
6. If it becomes `FAILED`, inspect its error while the preceding generation remains live.

## Purge runbook

Archive before purging. Confirm durable database/object-store backups, submit the exact crate name, and monitor the crate status. Filesystem deletion is constrained beneath `crates/{crateId}` and the Lucene crate root; S3 deletion lists only the crate prefix.

## Disaster recovery

Crate export is portability, not installation disaster recovery. Back up PostgreSQL/H2, the filesystem or S3 bucket, and deployment secrets using infrastructure-native tools. OpenSearch/Lucene data is derived and may be rebuilt from normalized documents.

## See also

- [Installation](installation/README.md): the administrator's path.
- [Deployment](deployment.md): scaling roles.
- [Pipeline](pipeline.md): stages, retries and dead letters.
- [Troubleshooting](troubleshooting.md) and [Export, import and recovery](backup-migration.md).
