# Backup and migration

Backups are ZIP bundles with a schema-versioned manifest, JSONL records, per-entry SHA-256 checksums, and optional raw artifacts. Lucene/OpenSearch internals are never copied; the target index is rebuilt.

API operations:

- `POST /api/v1/backups?includeArtifacts=true`
- `POST /api/v1/backups/validate` with multipart field `file`
- `POST /api/v1/backups/restore` with multipart field `file`

Restore requires an empty target database, verifies checksums before importing, writes artifacts through the selected adapter, recreates unfinished work, and queues every normalized document for target-index rebuild. Retain the source installation and bundle until counts and target health are verified.

Administrative users and external service credentials are deliberately not exported. Create the destination administrator and inject destination secrets separately.
