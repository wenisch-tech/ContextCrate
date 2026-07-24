# Crate export, import, and recovery

Crate exports are portable ZIP bundles containing `manifest.json`, `data.json`, optional artifacts, and SHA-256 checksums for every payload entry.

```bash
curl -X POST -H "X-API-KEY: $OWNER_KEY" \
  -o crate.zip "http://localhost:8080/api/v1/crates/$CRATE/exports?includeArtifacts=true"
curl -X POST -H "X-API-KEY: $PERSONAL_KEY" \
  -F "file=@crate.zip" "http://localhost:8080/api/v1/crate-imports/validate"
curl -X POST -H "X-API-KEY: $PERSONAL_KEY" \
  -F "file=@crate.zip" "http://localhost:8080/api/v1/crate-imports"
```

Import creates a new crate and remaps job, run, frontier, fetch, document, chunk, rule, and result identifiers. Artifacts receive new crate-prefixed keys. Imported documents are queued for a fresh vector namespace.

Secrets, users, memberships, API keys, elevations, and audit records are intentionally absent. Configure crawler and provider credentials after import.

!!! warning
    A crate export is not a complete installation backup. Use database and object-store snapshots for disaster recovery and keep all components from the same recovery point.

For the breaking product upgrade, follow [Upgrade from Harvex](upgrade-from-harvex.md).
