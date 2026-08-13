# JAR

Run ContextCrate directly on a JVM when Docker is not required. This mode uses the standalone profile unless explicitly configured otherwise.

## Prerequisites

- JDK 25 or newer
- Persistent storage for the application data directory

## Build and run

```bash
./mvnw -DskipTests package
CONTEXTCRATE_ADMIN_PASSWORD=change-me \
  java -jar target/contextcrate-*.jar
```

On Windows PowerShell:

```powershell
$env:CONTEXTCRATE_ADMIN_PASSWORD = 'change-me'
java -jar target/contextcrate-*.jar
```

Open `http://localhost:8080` and sign in as `admin@contextcrate.local`. Configure `CONTEXTCRATE_ADMIN_EMAIL` to use a different initial administrator email.

## Persistent data

By default, standalone data is stored beneath `data/` in the current working directory. Keep that directory on persistent storage and back it up before upgrades. It contains the H2 database, raw artifacts, Lucene index, and local model cache.

For a managed or scalable production deployment, prefer [Kubernetes](kubernetes.md) instead of operating a distributed JVM topology directly.
