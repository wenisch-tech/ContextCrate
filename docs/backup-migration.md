# Crate export, import, and recovery

Exports carry the crate's system prompt (applied after an import only while the administrator allows crate prompts), the document enrichment settings (the generated questions themselves are derived data and are written again by the next index rebuild), the context window setting and the stored overlap of every chunk; chunks from older exports have none stored and are joined with a detected overlap until the source is ingested again (see [Context window](features/context-window.md)). Evaluation settings, cases and the golden set of [RAG evaluation](features/evaluation.md) are not exported. Exports include proposition evaluations, accepted and rejected propositions, scores, and retrieval settings. Older schemas import as standard retrieval with `fail-indexing` with HyDE off and without contextual chunk headers (`off`, 1,000 words). Imported evaluations are reused only while their source/model/prompt fingerprint remains current.

Crate exports are portable ZIP bundles containing `manifest.json`, `data.json`, optional artifacts, and SHA-256 checksums for every payload entry.

```bash
curl -X POST -H "X-API-KEY: $OWNER_KEY" \
  -o crate.zip "http://localhost:8080/api/v1/crates/$CRATE/exports?includeArtifacts=true"
curl -X POST -H "X-API-KEY: $PERSONAL_KEY" \
  -F "file=@crate.zip" "http://localhost:8080/api/v1/crate-imports/validate"
curl -X POST -H "X-API-KEY: $PERSONAL_KEY" \
  -F "file=@crate.zip" "http://localhost:8080/api/v1/crate-imports"
```

Import creates a new crate and remaps source, ingestion-job, run, source-item, acquisition,
document, chunk, rule, and result identifiers. Artifacts receive new crate-prefixed keys. Imported
documents are queued for a fresh vector namespace. Schema-v1 bundles are accepted and their crawl
jobs become Website source/ingestion-job pairs.

Secrets, users, memberships, API keys, elevations, and audit records are intentionally absent.
Configure source and provider credentials after import.

:::warning
A crate export is not a complete installation backup. Use database and object-store snapshots for disaster recovery and keep all components from the same recovery point.
:::

For the breaking product upgrade, follow [Upgrade from Harvex](upgrade-from-harvex.md).

## See also

- [Crates](crates.md#portable-crates), [Upgrade from Harvex](upgrade-from-harvex.md), [Operations](operations.md).
- [Tutorials](tutorials.md#export-and-import): the API calls.
