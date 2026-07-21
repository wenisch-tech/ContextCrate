# Harvex

Harvex turns crawl configurations into a durable pipeline of raw artifacts, normalized documents, chunks, and search-index records.

The default standalone profile runs in one JVM with file-backed H2, a database queue, filesystem artifacts, and Lucene. The distributed profile runs independently scalable roles with PostgreSQL, RabbitMQ, S3, and OpenSearch. Both modes share the same domain and work contracts.

## Version 1 outcome

1. Create a crawl job with scope, politeness, reliability, and output settings.
2. Start an immutable run snapshot.
3. Fetch eligible pages while enforcing robots and network safety.
4. Store raw content outside the queue.
5. Parse, normalize, extract links and metadata, and create stable chunks.
6. Index document and chunk records.

Retrieval supports lexical BM25, semantic-vector, and RRF-hybrid modes. The default embedding provider runs a local multilingual ONNX model; an OpenAI-compatible embeddings endpoint can be selected instead. The remaining roadmap covers feedback, evaluation, reranking, and LLM answer generation.
