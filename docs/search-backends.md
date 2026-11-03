# Search backends

ContextCrate stores its search index in one of three backends. Everything above the index (ingestion, chunking,
retrieval options, answers, MCP, evaluation) behaves the same on all of them; the backend only decides where the
keyword index and the vectors live.

| | **Lucene** | **OpenSearch** | **Qdrant** |
|---|---|---|---|
| `contextcrate.index.backend` | `lucene` (default) | `opensearch` | `qdrant` |
| Runs | Inside the ContextCrate process | Separate cluster | Separate server or cluster |
| Best for | Local use, single-instance installs | Distributed installs that already run OpenSearch | Teams that run Qdrant, or want a dedicated vector database |
| Keyword search | BM25 | BM25 | BM25-style (sparse vectors with IDF, see below) |
| Vector search | HNSW (cosine) | HNSW (cosine) | HNSW (cosine) |
| Hybrid search | Reciprocal-rank fusion | Reciprocal-rank fusion | Reciprocal-rank fusion |
| Several ContextCrate processes | One indexer process (`role=all`) | Yes | Yes |
| Authentication | Not applicable | Not configured (secure the network) | Optional API key |

The backend is a **deployment** setting, not a crate setting. All crates of an installation use the same backend;
each crate and each index generation has its own physical index or collection (see [Crates](crates.md)).

## Choosing the backend

Set these properties (or their environment variables):

| Property | Environment variable | Meaning |
|---|---|---|
| `contextcrate.index.backend` | `CONTEXTCRATE_INDEX_BACKEND` | `lucene`, `opensearch` or `qdrant` |
| `contextcrate.index.endpoint` | `CONTEXTCRATE_INDEX_ENDPOINT` | Base URL of OpenSearch (default `http://localhost:9200`) or Qdrant (default `http://localhost:6333`) |
| `contextcrate.index.api-key` | `CONTEXTCRATE_INDEX_API_KEY` | Qdrant API key, sent as the `api-key` header. Leave empty for none |
| `contextcrate.index.prefix` | `CONTEXTCRATE_INDEX_PREFIX` | Prefix of the index or collection names (default `contextcrate`) |
| `contextcrate.index.path` | `CONTEXTCRATE_INDEX_PATH` | Lucene only: directory of the index files |

The distributed profile still defaults to OpenSearch and also accepts the older `OPENSEARCH_ENDPOINT` and `OPENSEARCH_PREFIX`.

### Qdrant

```bash
docker run -p 6333:6333 -v qdrant:/qdrant/storage qdrant/qdrant:v1.15.0

CONTEXTCRATE_INDEX_BACKEND=qdrant \
CONTEXTCRATE_INDEX_ENDPOINT=http://localhost:6333 \
java -jar contextcrate.jar
```

With Docker Compose, the distributed stack contains an optional Qdrant service:

```bash
CONTEXTCRATE_INDEX_BACKEND=qdrant CONTEXTCRATE_INDEX_ENDPOINT=http://qdrant:6333 \
  docker compose --profile qdrant -f compose.distributed.yml up
```

(The OpenSearch container of that stack is then unused and can be stopped.) Qdrant **1.10 or newer** is required, because ContextCrate uses the Query API.
For Qdrant Cloud or a secured server, use the HTTPS endpoint and set `CONTEXTCRATE_INDEX_API_KEY`.

## How Qdrant stores a crate

* One **collection per crate and index generation**: `{prefix}-crate-{crateId}-{generation}`. A rebuild creates the next generation, switches over, and then deletes the old collection, exactly as with the other backends.
* One **point per document** and one per **index record** of a chunk. That includes the chunk itself and, with [proposition retrieval](features/retrieval.md) or [document enrichment](features/document-enrichment.md), the propositions and generated questions that point back at their chunk. A search returns each chunk once.
* The payload carries the same fields as in OpenSearch (kind, document and chunk ids, title, heading, ordinal, text, source text, run id, record type), with payload indexes on `kind`, `parent_id`, `run_id` and `chunk_id` for fast filtering.
* Two vectors per point:
  * `dense`: the embedding (cosine distance). It exists when an embedding model is enabled; the vector size is the model's dimension. Changing the embedding model creates a new generation with the new size.
  * `text`: a **sparse vector** for keyword search.

## Keyword search on Qdrant

Qdrant has no analyzer and no BM25 ranking. ContextCrate gets the same effect with a sparse vector and the collection's `idf` modifier:

1. The text of a point (title counts three times, heading twice, plus the text and the URL) is split into terms by the same code-aware analyzer the Lucene backend uses: it lower-cases, and splits identifiers, so `getUserName` is found by `user` and `snake_case_name` by `case`; the original identifier is kept too.
2. Each term becomes an index (a 32-bit hash of the term). Its weight is the saturated term frequency `tf·(k1+1)/(tf+k1)` with `k1 = 1.2`.
3. A query is tokenized the same way with weight 1 per distinct term. Qdrant scores `Σ weight × IDF` and returns the best points.

Differences to BM25: there is no document-length normalization, and there is no stemming (the analyzer is the same as in Lucene). In practice the ranking is close; if you rely on exact BM25 behaviour, use Lucene or OpenSearch.

## Switching backends

The index is derived data: nothing else is lost when you switch.

1. Start the new backend and set `CONTEXTCRATE_INDEX_BACKEND` (and endpoint).
2. Restart ContextCrate and run **Index → Rebuild** for each crate (`POST /api/v1/crates/{crateId}/index/rebuild`). The rebuild reads the stored chunks, so sources are not crawled again, and the questions of [document enrichment](features/document-enrichment.md) and the headers of [contextual headers](features/contextual-headers.md) come from their caches.
3. The old backend can then be shut down. Until the rebuild finishes, searches in a crate return nothing from the new backend.

## Operations

* **Health:** the crate's index health shows the backend (`qdrant`), whether the collection answers, and the number of points.
* **Metrics:** the search metrics carry `backend="qdrant"`; see [Operations](operations.md#metrics).
* **Consistency:** every write waits for completion, so a document is searchable as soon as its indexing finished.
* **Failures:** a failed request becomes an indexing error with Qdrant's status and message; the document is retried like for any backend.
* **Backups:** back up the Qdrant storage or use its snapshots; because the index is derived from the database, an alternative is to rebuild it.

## Tuning

* Results from [document enrichment](features/document-enrichment.md) or propositions are candidates of the same limit; raise `contextcrate.retrieval.candidate-limit` if you use many questions per chunk.
* Keep Qdrant and ContextCrate close in the network: indexing sends the points in batches of 64.
