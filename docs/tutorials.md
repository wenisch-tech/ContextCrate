# Tutorials

## Create the first crate

Sign in, choose **Create your first crate**, enter a name and description, and open it. The creator becomes Owner. Use **Switch crate** to move between memberships.

## Crawl and search

1. Open **Crawl jobs** and create a job with a seed URL and allowed host.
2. Start a run and follow its frontier/fetch status.
3. Open **Documents** after parsing completes.
4. Search from the crate overview. Results always come from this crate's active namespace.

API equivalent:

```bash
curl -X POST -H "X-API-KEY: $KEY" -H "Content-Type: application/json" \
  "http://localhost:8080/api/v1/crates/$CRATE/jobs" \
  -d '{"name":"Docs","configuration":{"scope":{"seedUrl":"https://example.com","allowedHosts":["example.com"],"maxDepth":2,"maxPages":100}}}'
```

## Add a teammate

An installation administrator creates the account. A crate Owner then adds its email as Viewer, Editor, or Owner from the members API:

```bash
curl -X PUT -H "X-API-KEY: $OWNER_KEY" -H "Content-Type: application/json" \
  "http://localhost:8080/api/v1/crates/$CRATE/members" \
  -d '{"email":"editor@example.com","role":"EDITOR"}'
```

## Create a service key

```bash
curl -X POST -H "X-API-KEY: $OWNER_KEY" -H "Content-Type: application/json" \
  "http://localhost:8080/api/v1/crates/$CRATE/api-keys" \
  -d '{"name":"ingestion","role":"EDITOR"}'
```

Copy the returned `cc_` token immediately.

## Export and import

```bash
curl -X POST -H "X-API-KEY: $OWNER_KEY" \
  -o crate.zip "http://localhost:8080/api/v1/crates/$CRATE/exports?includeArtifacts=true"

curl -X POST -H "X-API-KEY: $PERSONAL_KEY" \
  -F "file=@crate.zip" "http://localhost:8080/api/v1/crate-imports"
```

The imported crate has new identifiers and no provider or crawler secrets.

## Change an embedding model

Update the crate provider settings. ContextCrate creates a background generation and keeps the prior generation searchable. Monitor `/api/v1/crates/{crateId}/index` until the new generation becomes `ACTIVE`.
