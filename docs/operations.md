# Operations

## Health boundaries

Installation administrators can inspect global backend and queue health under `/api/v1/admin/system`. Crate members inspect index health and generations under `/api/v1/crates/{crateId}/index`.

Failures are intentionally contained:

- An embedding or answer-provider failure affects only calls for the configured crate.
- A failed generation never replaces the active index.
- A purge failure leaves a `PURGE_FAILED` tombstone and can be retried.
- Dead-lettered work retains its crate identity.

## Metrics

Pipeline metrics use the `contextcrate.*` prefix:

- `contextcrate.pipeline.completed`
- `contextcrate.pipeline.failed`
- `contextcrate.pipeline.duration`

Stage is a low-cardinality tag. Crate IDs are deliberately not metric tags; use crate-scoped audit and generation endpoints for per-crate investigation.

## Index rebuild runbook

1. Verify the crate is `ACTIVE`.
2. Check provider credentials and model availability.
3. Start `POST /api/v1/crates/{crateId}/index/rebuild`.
4. Poll `GET /api/v1/crates/{crateId}/index`.
5. Confirm the new generation becomes `ACTIVE` and the document count matches expectations.
6. If it becomes `FAILED`, inspect its error while the preceding generation remains live.

## Purge runbook

Archive before purging. Confirm durable database/object-store backups, submit the exact crate name, and monitor the crate status. Filesystem deletion is constrained beneath `crates/{crateId}` and the Lucene crate root; S3 deletion lists only the crate prefix.

## Disaster recovery

Crate export is portability, not installation disaster recovery. Back up PostgreSQL/H2, the filesystem or S3 bucket, and deployment secrets using infrastructure-native tools. OpenSearch/Lucene data is derived and may be rebuilt from normalized documents.
