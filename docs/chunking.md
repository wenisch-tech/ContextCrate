# Chunking

ContextCrate converts every normalized document into ordered text chunks before extraction and
indexing. Chunks are the units used for embeddings, search retrieval, and RAG answer context.

## Defaults

New ingestion jobs use a **chunk size of 1,000 characters** with a **200-character overlap**.
These values are configurable per ingestion job in the **Content and chunks** section.

The chunk size is a character limit, not a token limit. The actual chunk can be slightly shorter
when ContextCrate finds a suitable word or line boundary near the limit.

## How chunks are created

1. Content is normalized from HTML, Markdown, or plain text.
2. Markdown headings define sections. Each resulting chunk retains its section heading as metadata.
3. Each section is divided into chunks of up to the configured size. When possible, a chunk ends
   at the last space or newline in the latter half of the chunk instead of splitting a word.
4. The following chunk begins up to the configured overlap before the previous chunk end.

For example, with a 1,000-character size and 200-character overlap, a long section normally
produces chunks covering characters `0–999`, `800–1799`, `1600–2599`, and so on. Boundary-aware
splitting can shift those end positions earlier.

## Overlap

Overlap preserves context across chunk boundaries. It helps retrieval when a sentence, heading,
or relevant concept spans two chunks. Larger overlap improves continuity but creates more chunks,
increasing storage and embedding work.

The overlap is clamped to a valid value: it cannot be negative or greater than half the configured
chunk size. During parsing it is also kept smaller than the effective chunk size to guarantee that
processing continues.

## Embedding limits

When an OpenAI-compatible embedding provider is selected, its **Maximum input characters** setting
places an additional upper bound on chunk size. If that provider limit is below the ingestion job's
chunk size, ContextCrate creates smaller chunks using the provider limit. This prevents oversized
embedding requests while preserving the configured overlap where possible.

Changing a job's chunk settings affects documents processed in later runs. Run the ingestion job
again to re-chunk existing source content.
