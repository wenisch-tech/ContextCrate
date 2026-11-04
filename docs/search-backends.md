# Search backends

ContextCrate stores its search index in one of five backends. Everything above the index (ingestion, chunking,
retrieval options, answers, MCP, evaluation) behaves the same on all of them; the backend only decides where the
keyword index and the vectors live.

| | **Lucene** | **OpenSearch** | **Elasticsearch** | **Qdrant** | **pgvector** |
|---|---|---|---|---|---|
| `contextcrate.index.backend` | `lucene` (default) | `opensearch` | `elasticsearch` | `qdrant` | `pgvector` |
| Runs | Inside the ContextCrate process | Separate cluster | Separate cluster | Separate server or cluster | Tables in a PostgreSQL database |
| Best for | Local use, single-instance installs | Distributed installs that already run OpenSearch | Teams that run Elasticsearch | Teams that run Qdrant or want a dedicated vector database | Distributed installs that want no extra search service: use the PostgreSQL they already have |
| Keyword search | BM25 | BM25 | BM25 | BM25-style (sparse vectors with IDF) | PostgreSQL full-text search (no IDF) |
| Vector search | HNSW (cosine) | HNSW (cosine) | HNSW (cosine) | HNSW (cosine) | HNSW (cosine), up to 2,000 dimensions |
| Hybrid search | Reciprocal-rank fusion | Reciprocal-rank fusion | Reciprocal-rank fusion | Reciprocal-rank fusion | Reciprocal-rank fusion |
| Several ContextCrate processes | One indexer process (`role=all`) | Yes | Yes | Yes | Yes |
| Authentication | Not applicable | Not configured (secure the network) | API key or user and password | Optional API key | PostgreSQL user and password |
| Requires | Nothing | OpenSearch 2 or 3 | Elasticsearch 8 or 9 | Qdrant 1.10 or newer | PostgreSQL 13 or newer with the `vector` extension (pgvector 0.5 or newer) |

The backend is a **deployment** setting. All crates of an installation use the same backend unless the administrator allows [dedicated search backends per crate](features/crate-backends.md), where a crate can be created with an OpenSearch, Elasticsearch, Qdrant or pgvector service of its own;
each crate and each index generation has its own physical index, collection or table (see [Crates](crates.md)).

## Choosing the backend

Set these properties (or their environment variables):

| Property | Environment variable | Meaning |
|---|---|---|
| `contextcrate.index.backend` | `CONTEXTCRATE_INDEX_BACKEND` | `lucene`, `opensearch`, `elasticsearch`, `qdrant` or `pgvector` |
| `contextcrate.index.endpoint` | `CONTEXTCRATE_INDEX_ENDPOINT` | Base URL of OpenSearch or Elasticsearch (default `http://localhost:9200`) or Qdrant (default `http://localhost:6333`); a `jdbc:postgresql:` URL for pgvector (blank: the application's own PostgreSQL database) |
| `contextcrate.index.api-key` | `CONTEXTCRATE_INDEX_API_KEY` | Qdrant API key (`api-key` header) or Elasticsearch API key (`Authorization: ApiKey`). Leave empty for none |
| `contextcrate.index.username`, `contextcrate.index.password` | `CONTEXTCRATE_INDEX_USERNAME`, `CONTEXTCRATE_INDEX_PASSWORD` | Elasticsearch basic authentication, or the PostgreSQL user for pgvector |
| `contextcrate.index.trust-all-certificates` | `CONTEXTCRATE_INDEX_TRUST_ALL_CERTIFICATES` | Accept any TLS certificate of the Elasticsearch or Qdrant endpoint. For development only, default `false` |
| `contextcrate.index.prefix` | `CONTEXTCRATE_INDEX_PREFIX` | Prefix of the index, collection and table names (default `contextcrate`; for pgvector 1 to 20 characters from `a-z`, `0-9` and `_`, starting with a letter) |
| `contextcrate.index.path` | `CONTEXTCRATE_INDEX_PATH` | Lucene only: directory of the index files |

The distributed profile still defaults to OpenSearch and also accepts the older `OPENSEARCH_ENDPOINT` and `OPENSEARCH_PREFIX`.

### Elasticsearch

```bash
CONTEXTCRATE_INDEX_BACKEND=elasticsearch \
CONTEXTCRATE_INDEX_ENDPOINT=https://elasticsearch.example.com:9200 \
CONTEXTCRATE_INDEX_API_KEY=<base64 api key> \
java -jar contextcrate.jar
```

Elasticsearch **8.0 or newer** is required (native `knn` search). The setup mirrors OpenSearch: one index per crate and generation named
`{prefix}-crate-{crateId}-{generation}`, BM25 keyword search over title, heading, text and URL with a code-aware analyzer (identifiers are split, so `getUserName` is found by `user`),
a `dense_vector` field with cosine similarity for semantic search, and hybrid search by reciprocal-rank fusion.

* **Security:** Elasticsearch 8 enables authentication and TLS by default. Use an API key (`CONTEXTCRATE_INDEX_API_KEY`) or `CONTEXTCRATE_INDEX_USERNAME` and `CONTEXTCRATE_INDEX_PASSWORD`. A self-signed certificate is rejected unless it is in the JVM trust store; for a local evaluation only, set `CONTEXTCRATE_INDEX_TRUST_ALL_CERTIFICATES=true`.
* **Permissions:** the user or key needs to create and delete indexes named `{prefix}-crate-*`, to write and search them, and to list indexes (`monitor`/`view_index_metadata`).
* **Deleting a crate** removes its indexes by name, because Elasticsearch refuses wildcard deletes by default.
* **Failed writes** are reported: the bulk response is checked for per-document errors.

With Docker Compose: `CONTEXTCRATE_INDEX_BACKEND=elasticsearch CONTEXTCRATE_INDEX_ENDPOINT=http://elasticsearch:9200 docker compose --profile elasticsearch -f compose.distributed.yml up` (security is disabled in this evaluation service).

### pgvector

pgvector keeps the search index in PostgreSQL, so a distributed installation can run on a single database server.

```bash
# the application's own PostgreSQL (it must have the vector extension)
CONTEXTCRATE_INDEX_BACKEND=pgvector java -jar contextcrate.jar

# or any other PostgreSQL, for example next to the standalone H2 database
CONTEXTCRATE_INDEX_BACKEND=pgvector \
CONTEXTCRATE_INDEX_ENDPOINT=jdbc:postgresql://db.example.com:5432/contextcrate_index \
CONTEXTCRATE_INDEX_USERNAME=contextcrate CONTEXTCRATE_INDEX_PASSWORD=secret \
java -jar contextcrate.jar
```

* **Connection:** a `jdbc:postgresql:` endpoint is used as given. With a blank endpoint the index uses the application's own database and credentials, which requires PostgreSQL (`SPRING_DATASOURCE_URL`). The index always opens its **own small connection pool** (at most 4 connections), so its transactions are independent of the application's.
* **Extension:** PostgreSQL needs the `vector` extension (the `pgvector/pgvector` image has it; managed services offer it as a setting). ContextCrate runs `CREATE EXTENSION IF NOT EXISTS vector` once; if the database user may not do that, let an administrator create the extension first. The application database image of the distributed Compose stack does not include it: use `--profile pgvector` or your own PostgreSQL.
* **Tables:** one table per crate and generation, `{prefix}_c{crateId without dashes}_{generation}`, with a `vector(N)` column (N is the embedding dimension), a `tsvector` column, and indexes on `parent_id`, `kind`, `run_id`, a GIN index for the full text and an HNSW index (`vector_cosine_ops`) for the vectors. Deleting a crate drops its tables; a rebuild creates the next generation's table. The database user needs the right to create and drop tables.
* **Vector size:** the HNSW index supports up to **2,000 dimensions**. For larger embeddings the table is created without the vector index and searches scan it exactly: correct, but slower on big crates.
* **Keyword search** is PostgreSQL full-text search with the `simple` configuration (no language-specific stemming, so it works for any language). Title, heading, text and URL are weighted A to D, and terms come from ContextCrate's code-aware analyzer, so identifiers are split into their parts. Ranking uses `ts_rank`, which, unlike BM25, has **no inverse document frequency**: rare and common words weigh the same. If keyword ranking matters most for a crate, prefer Lucene, OpenSearch or Elasticsearch, or rely on hybrid search where the vector side compensates.
* **Filtered vector search:** pgvector applies filters after the index scan. ContextCrate raises `hnsw.ef_search` (and uses iterative scans with pgvector 0.8 or newer) so that filtered searches still return enough rows.

With Docker Compose: `CONTEXTCRATE_INDEX_BACKEND=pgvector CONTEXTCRATE_INDEX_ENDPOINT=jdbc:postgresql://pgvector:5432/contextcrate CONTEXTCRATE_INDEX_USERNAME=contextcrate CONTEXTCRATE_INDEX_PASSWORD=contextcrate docker compose --profile pgvector -f compose.distributed.yml up`.

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

* One **collection per crate and index generation**: `{prefix}-crate-{crateId}-{generation}`. A rebuild fills the next generation and then switches the crate over to it, exactly as with the other backends.
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
3. The old backend can then be shut down. The app does not delete the physical indexes of retired generations; drop them in the old service yourself if you want the space back. Until the rebuild finishes, searches in a crate return nothing from the new backend.

## Operations

* **Health:** the crate's index health shows the backend (`qdrant`, `elasticsearch`, `pgvector`, …), whether the index answers, and the number of records.
* **Metrics:** the search metrics carry the backend name (for example `backend="elasticsearch"`); see [Operations](operations.md#metrics).
* **Consistency:** every write waits for completion, so a document is searchable as soon as its indexing finished.
* **Failures:** a failed request becomes an indexing error with the service's status and message; the document is retried like for any backend.
* **Backups:** back up the storage of the search service (Qdrant snapshots, Elasticsearch snapshots, the PostgreSQL database for pgvector); because the index is derived from the database, an alternative is to rebuild it.

## Tuning

* Results from [document enrichment](features/document-enrichment.md) or propositions are candidates of the same limit; raise `contextcrate.retrieval.candidate-limit` if you use many questions per chunk.
* Keep Qdrant and ContextCrate close in the network: indexing sends the points in batches of 64.
