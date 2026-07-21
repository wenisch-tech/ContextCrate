# Retrieval and ranking roadmap

1. Expand BM25 retrieval over the existing document/chunk fields with richer filters, highlighting, and evaluation fixtures.
2. Add embedding generation and versioned Lucene/OpenSearch vector mappings, followed by hybrid score fusion. **Implemented:** configurable local ONNX and OpenAI-compatible providers, semantic retrieval, and RRF fusion.
3. Capture query, impression, click, conversion, dwell, and explicit feedback events with privacy controls.
4. Introduce offline judgments, reproducible evaluation sets, and NDCG/MRR/recall dashboards.
5. Add pluggable rerankers, feature logging, model/version registries, shadow evaluation, and learning-to-rank rollout controls.

No crawler or normalized-document contract needs to change for these phases; new index versions are rebuilt from the canonical relational data.
