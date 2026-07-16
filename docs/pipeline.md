# Pipeline behavior

## Frontier

Every discovered URL is canonicalized before insertion. A `(run_id, canonical_url)` constraint supplies the final deduplication boundary. Scope is checked both for seeds and discovered links.

## Work lifecycle

Local work moves through `PENDING`, `PROCESSING`, `RETRY_WAITING`, `COMPLETED`, and `DEAD_LETTERED`. Claims carry an expiry lease. Failures use exponential backoff and stop at the configured attempt ceiling. Operators can inspect and requeue database DLQ records.

## Crawling

Java `HttpClient` is the normal fetcher. Redirect targets are resolved and safety-checked on every hop. `AUTO` first fetches HTML normally and requests one browser-rendered fetch when the response resembles a JavaScript shell. Browser workers run Playwright/Chromium and should be isolated from the control plane.

## Parsing and indexing

JSoup applies removal and content selectors, normalizes Unicode/whitespace, and extracts titles, language, description, author, headings, Open Graph values, and links. Chunks use deterministic identifiers and overlap. Lucene and OpenSearch receive equivalent field names and separate document/chunk records.
