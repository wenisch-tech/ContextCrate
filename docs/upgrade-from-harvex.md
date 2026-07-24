# Upgrade from Harvex

ContextCrate is a breaking rename and data-model upgrade. Java packages, Maven artifacts, configuration keys, environment variables, container/Helm names, metrics, default H2 filename, and content APIs changed.

!!! danger
    Stop all control-plane and worker processes and take verified backups before upgrading. Do not run old and new binaries against the same database.

## Identifier changes

| Old | New |
|---|---|
| `tech.wenisch.harvex` | `tech.wenisch.contextcrate` |
| `harvex.*` | `contextcrate.*` |
| `HARVEX_*` | `CONTEXTCRATE_*` |
| `harvex-*.jar` | `contextcrate-*.jar` |
| `/api/v1/jobs` | `/api/v1/crates/{crateId}/sources` plus nested ingestion jobs |
| Global index | One versioned namespace per crate |

No legacy environment-variable aliases are accepted.

## Standalone H2 migration

From the repository root:

```powershell
.\scripts\migrate-harvex-to-contextcrate.ps1 -DataDirectory .\data
.\scripts\migrate-harvex-to-contextcrate.ps1 -DataDirectory .\data -Apply
```

The script refuses to overwrite an existing ContextCrate database and copies rather than deletes the source. It also copies filesystem artifacts into the deterministic Legacy crate namespace, resumes safely when a destination has the same checksum, and stops on a conflict. Start ContextCrate afterward. Flyway creates the **Legacy** crate, attaches existing content/settings, rewrites artifact references, and converts existing API keys to Legacy-crate Editor keys.

New artifacts use `crates/{crateId}/...`. Keep the old database and artifact paths until the Legacy crate has been rebuilt, searched, exported, and validated.

The source/ingestion migration creates one Website source and one ingestion job for every
existing crawl job. It retains IDs, historical runs, artifacts, documents, and extraction
results. The old `/jobs` contract has no compatibility alias.

## PostgreSQL and distributed installations

The external database, bucket, exchange, or OpenSearch service does not need to be physically renamed. Update environment-variable names while retaining explicit connection values. Upgrade database migrations before starting stage workers; consumers accept schema-v1 messages during the transition and new publishers emit schema v2.

## Verification

- Sign in and confirm the Legacy crate membership.
- Compare source, ingestion-job, run, document, and extraction counts.
- Start a Legacy crate index rebuild and verify search.
- Download a known artifact.
- Create a second crate and confirm overlapping content does not cross search results.
- Create new API keys for automation using crate-qualified routes.

Rollback requires stopping ContextCrate and restoring the complete pre-upgrade database and durable storage together.
