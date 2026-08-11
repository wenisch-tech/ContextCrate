# Pipeline behavior

During the index stage, crates using proposition retrieval add generation and grading before embedding. Matching persisted evaluations are reused. Failures follow the crate's proposition failure policy; `fail-indexing` uses the existing retry and dead-letter behavior. Generation rebuilds keep the old index active until the replacement succeeds.

## Source items

Every discovered locator is converted to a source URI before insertion. A `(run_id, source_uri)`
constraint supplies the final deduplication boundary. Website jobs canonicalize URLs and check
scope for seeds and discovered links. Git jobs use repository, resolved commit, and path.

## Work lifecycle

Local work moves through `PENDING`, `PROCESSING`, `RETRY_WAITING`, `COMPLETED`, and `DEAD_LETTERED`. Claims carry an expiry lease. Failures use exponential backoff and stop at the configured attempt ceiling. Operators can inspect and requeue database DLQ records.

## Crawling

Java `HttpClient` is the normal fetcher. Redirect targets are resolved and safety-checked on every hop. `AUTO` first fetches HTML normally and requests one browser-rendered fetch when the response resembles a JavaScript shell. Browser workers run Playwright/Chromium and should be isolated from the control plane.

## Git acquisition

The Git connector clones one HTTPS repository snapshot into an isolated temporary directory.
Blank refs use the remote default branch; explicit branches, tags, and reachable commits are
resolved to an exact SHA stored on the ingestion run. Eligible UTF-8 `.md`, `.markdown`, and
`.txt` files become acquisition records and raw artifacts before normalization. Temporary clones
are removed after success or failure.

## Parsing, extraction, and indexing

JSoup normalizes website HTML and extracts titles, language, description, author, headings, Open
Graph values, and links. Markdown normalization produces readable text, chooses the first heading
as title, and retains heading context on chunks; plain text preserves readable line and paragraph
boundaries. Chunks use deterministic identifiers and overlap. Lucene and OpenSearch receive
equivalent field names and separate document/chunk records.

For OpenAI-compatible embedding providers, parsing also applies the configured maximum embedding
input-character limit to chunks. If the ingestion chunk size is larger, the parser creates smaller
persisted chunks before indexing. This avoids sending an oversized chunk to the embedding endpoint
and lets semantic retrieval cover every section of a long source document.

Extraction is derived work over normalized `DocumentChunk.content`. Enabled extraction rules run after chunks are saved and before run completion; the first rule types are built-in IP address detection and user-supplied regular expressions. Matches are stored as relational rows with the rule, run, document, chunk, offsets, matched value, and a small context window. Extraction does not change crawling, raw artifacts, normalized document bodies, chunk IDs, or search-index contracts.

Extraction results are rebuildable. Rebuilding a run or document deletes the scoped derived matches and enqueues fresh extraction work with a new work idempotency key, while automatic extraction uses stable document-level idempotency. Rule deletion disables the rule so historical result rows keep their rule reference.

Search is query-side retrieval over the same Lucene/OpenSearch records created by indexing. Lexical mode uses BM25 scoring across title, heading, URL text, and normalized text fields; semantic mode embeds the query and uses nearest-neighbour vector search; hybrid mode fuses both ranked lists with RRF. Optional run and kind filters apply to every mode. Search does not create crawl or extraction work; it reads the latest committed index. Rebuild the derived vector index from canonical records with `POST /api/v1/index/rebuild` after changing embedding models.

## Document versions

Running an ingestion job again does not duplicate unchanged documents. ContextCrate identifies a
document by its source and canonical URL (or, for Git, its repository-relative path). Changed
normalized content creates the next immutable version; unchanged content creates no version.

The Documents list, document count, index rebuilds, and normal search use only the current version.
Select a version number in the Documents screen, or call
`GET /api/v1/crates/{crateId}/documents/{id}/versions`, to view the history. Older versions retain
their original ingestion run and are excluded from default retrieval.

When upgrading to this behavior, the database migration retains only the newest pre-existing record
for each source/URL pair. Rebuild the index once after deployment so any previously indexed
duplicates are removed from the search backend.
