# Docker

Use Docker to run one standalone ContextCrate instance from the published container image.

## Prerequisites

- Docker Engine or Docker Desktop
- A persistent Docker volume
- A strong initial administrator password

## Run

```bash
docker volume create contextcrate-data

docker run --name contextcrate --detach \
  --publish 8080:8080 \
  --volume contextcrate-data:/app/data \
  --env CONTEXTCRATE_ADMIN_PASSWORD=change-me \
  ghcr.io/wenisch-tech/contextcrate:latest
```

Open `http://localhost:8080` and sign in as `admin@contextcrate.local` with the password supplied in `CONTEXTCRATE_ADMIN_PASSWORD`.

Use `CONTEXTCRATE_ADMIN_EMAIL` to select a different initial administrator email. The initial credentials only establish the administrator account; protect them as deployment secrets.

## Operations

```bash
docker logs --follow contextcrate
docker stop contextcrate
docker start contextcrate
```

The container's `/app/data` volume holds the standalone H2 database, filesystem artifacts, Lucene index, and locally downloaded model cache. Back up this volume before upgrades and restore all of it together.

Docker runs the standalone profile only. For a production deployment with independently scalable roles and external backing services, use [Kubernetes](kubernetes.md).
