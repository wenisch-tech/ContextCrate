# Retrieval and ranking roadmap

1. Expand BM25 retrieval over the existing document/chunk fields with richer filters, highlighting, and evaluation fixtures.
2. Add embedding generation and versioned Lucene/OpenSearch vector mappings, followed by hybrid score fusion. **Implemented:** configurable local ONNX and OpenAI-compatible providers, semantic retrieval, and RRF fusion.
3. Capture query, impression, click, conversion, dwell, and explicit feedback events with privacy controls.
4. Introduce offline judgments, reproducible evaluation sets, and NDCG/MRR/recall dashboards.
5. Add pluggable rerankers, feature logging, model/version registries, shadow evaluation, and learning-to-rank rollout controls.

Answer generation is now available through a configured OpenAI-compatible streaming endpoint. Feedback and evaluation remain prerequisites for ranking optimization.

New index versions are rebuilt from the canonical relational data.

## Source ingestion roadmap

The first Git connector deliberately supports full snapshots of UTF-8 Markdown and text over
HTTPS. Later ingestion work will add:

1. SSH credentials and verified host keys, encrypted credential storage, and external secret references.
2. Incremental commit-diff ingestion, webhooks, schedules, and deletion reconciliation.
3. Sparse checkout, multi-branch jobs, repository history, submodules, and Git LFS object retrieval.
4. Repository-wide transfer/storage quotas and stronger redirect-aware transport policy.
5. Additional encodings and normalizers for HTML files, source code, PDF, and Office documents.

Current limitations: no SSH, scheduling, webhooks, incremental synchronization, history,
submodules, LFS objects, symlinks, or repository-wide transfer quota. Only `.md`, `.markdown`, and
`.txt` are accepted; files must be valid UTF-8. Git tokens are redacted from APIs, logs, audits,
and exports but remain plaintext in the ingestion-job configuration JSON.
