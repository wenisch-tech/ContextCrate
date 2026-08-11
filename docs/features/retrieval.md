# Retrieval

ContextCrate supports two crate-scoped chunk retrieval strategies. **Standard retrieval** is the default and indexes each source chunk directly. **Proposition retrieval** converts each chunk into independently understandable factual statements and indexes only statements that pass grading. Document-level records are unchanged.

Configure the strategy on the crate **Settings** page or with `PUT /api/v1/crates/{crateId}/settings/rag`:

```json
{
  "strictGrounding": false,
  "allowClientHistory": true,
  "inlineCitations": true,
  "structuredSources": true,
  "retrievalMode": "hybrid",
  "retrievalStrategy": "proposition",
  "propositionFailurePolicy": "fail-indexing",
  "sourceLimit": 8
}
```

Proposition retrieval requires enabled, configured embedding and answer providers. It reuses the crate's OpenAI-compatible answer endpoint, model, credentials, headers, timeout, and output-token limit. Generation and grading are deterministic non-streaming requests with temperature zero.

## Generation and grading

ContextCrate makes two independent LLM calls for each chunk. The first asks for JSON propositions. Every proposition must state one fact, work without additional context, replace pronouns and ambiguous references with full names, preserve dates, data, names, and qualifications, and contain a clear subject, verb, and object.

The second call starts with a new message context and receives the original chunk plus all generated propositions. It assigns four integer scores from 1 through 10:

1. Fidelity to the original statement.
2. Comprehensibility without further context.
3. Completeness of stated data, names, and qualifications.
4. Focus and freedom from unnecessary words.

A proposition is accepted only when **every score is 8–10**. Scores are never averaged. Malformed JSON, mismatched ordinals, missing ratings, out-of-range scores, provider errors, and a result with no accepted propositions invoke the configured failure policy.

## Failure policies

| Policy | Behavior |
| --- | --- |
| `fail-indexing` | Default. Fail the index task so the pipeline retries it and eventually exposes failed work. |
| `skip-chunk` | Continue indexing but omit the affected chunk from retrieval. |
| `embed-source-chunk` | Index the original chunk, allowing a deliberately mixed proposition/standard generation. |

Generation-based rebuilds keep the previous active index when a new generation fails.

## Storage and search behavior

Evaluations persist with their model, prompt version, source hash, status, diagnostics, proposition text, four scores, and acceptance state. A matching fingerprint is reused during rebuilds; source, model, endpoint, or prompt changes invalidate it. The document details page and chunk propositions API expose accepted and rejected results.

Each accepted proposition becomes a separate lexical and vector record, but retains the original chunk ID and source text. ContextCrate deduplicates records by source chunk before fusion, limiting, reranking, or answer-source selection. The strongest proposition supplies the search snippet and reranking text; answer generation and citations receive the complete original chunk.

## Cost and privacy

Proposition retrieval adds two LLM requests for every uncached chunk plus embedding work for every accepted proposition. Source chunks and generated propositions are sent to the configured answer provider. Prompts and credentials are not written to audit logs, but evaluations and scores are stored in the crate database. Use TLS and treat the provider as a data processor.
