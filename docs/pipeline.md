# Pipeline behavior

## Frontier

Every discovered URL is canonicalized before insertion. A `(run_id, canonical_url)` constraint supplies the final deduplication boundary. Scope is checked both for seeds and discovered links.

## Work lifecycle

Local work moves through `PENDING`, `PROCESSING`, `RETRY_WAITING`, `COMPLETED`, and `DEAD_LETTERED`. Claims carry an expiry lease. Failures use exponential backoff and stop at the configured attempt ceiling. Operators can inspect and requeue database DLQ records.

## Crawling

Java `HttpClient` is the normal fetcher. Redirect targets are resolved and safety-checked on every hop. `AUTO` first fetches HTML normally and requests one browser-rendered fetch when the response resembles a JavaScript shell. Browser workers run Playwright/Chromium and should be isolated from the control plane.

## Parsing, extraction, and indexing

JSoup applies removal and content selectors, normalizes Unicode/whitespace, and extracts titles, language, description, author, headings, Open Graph values, and links. Chunks use deterministic identifiers and overlap. Lucene and OpenSearch receive equivalent field names and separate document/chunk records.

Extraction is derived work over normalized `DocumentChunk.content`. Enabled extraction rules run after chunks are saved and before run completion; the first rule types are built-in IP address detection and user-supplied regular expressions. Matches are stored as relational rows with the rule, run, document, chunk, offsets, matched value, and a small context window. Extraction does not change crawling, raw artifacts, normalized document bodies, chunk IDs, or search-index contracts.

Extraction results are rebuildable. Rebuilding a run or document deletes the scoped derived matches and enqueues fresh extraction work with a new work idempotency key, while automatic extraction uses stable document-level idempotency. Rule deletion disables the rule so historical result rows keep their rule reference.
