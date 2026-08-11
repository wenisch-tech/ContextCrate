# Architecture

Chunk indexing is crate-configurable. Standard retrieval sends chunks directly to the search backend. Proposition retrieval first uses the answer model to generate and grade facts, persists the evaluation, and indexes accepted propositions while retaining source-chunk identity. See [Retrieval](features/retrieval.md).

ContextCrate is a Spring Boot application that can run all stages in one process or distribute
them across control-plane, website-source, Git-source, browser, parser, extractor, and indexer
roles.

```mermaid
flowchart LR
  UI[Crate-qualified UI/API] --> AUTH[Membership authorization]
  AUTH --> DB[(Relational store)]
  DB --> Q[Crate-aware work queue]
  Q --> C[Website/Git connectors]
  C --> A[(crates/{crateId} artifacts)]
  C --> P[Normalize and discover]
  P --> D[(Documents and chunks)]
  D --> E[Extraction]
  D --> I[Generation-aware indexer]
  I --> N[(Crate index namespace)]
  UI --> N
```

## Ownership propagation

`crate_id` is stored on every crate-owned row rather than inferred only through joins. Pipeline schema v2 duplicates it in the envelope and JSON payload. This deliberate redundancy lets repositories scope queries efficiently and lets workers detect inconsistent or malicious references before doing work.

Delivery is at least once. Idempotency keys include crate identity, and entity relationships are verified at each stage.

## Source model

A source owns a stable endpoint and credentials. Any number of ingestion jobs may reference it,
each with its own scope, ref, filters, limits, and normalization policy. An ingestion run snapshots
both configurations. Connector-specific acquisition ends at `AcquisitionRecord`; normalization,
extraction, and indexing are source-neutral.

## Storage adapters

The relational database owns canonical configuration and normalized content. Raw bodies use `ArtifactStore` with filesystem or S3 implementations. Search uses `SearchIndex` with Lucene or OpenSearch implementations. Index data and embeddings are derived and rebuildable.

Backends are installation-wide services, while prefixes/directories and configuration are crate-specific.

## Provider resolution

Embedding and answer settings are keyed by crate. An execution context selects the correct provider configuration for crawl-index work, search queries, and asynchronous answer streaming. Local model assets may share an installation cache, but vector namespaces and credentials do not.

## Generation switch

```mermaid
sequenceDiagram
  participant O as Owner
  participant C as ContextCrate
  participant G1 as Active generation
  participant G2 as Building generation
  O->>C: Change embedding configuration
  C->>G2: Reindex normalized documents
  C->>G1: Continue serving search
  C->>G1: Write new documents
  C->>G2: Dual-write new documents
  C->>G2: Commit and verify
  C->>C: Atomically set active generation
```

If the build fails, its error is recorded and its namespace is removed; the previous active generation is unchanged.
