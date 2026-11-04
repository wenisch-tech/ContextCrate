package tech.wenisch.contextcrate.index;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Runs the shared expectations against a real PostgreSQL with pgvector: {@code PGVECTOR_URL=jdbc:postgresql://localhost:5432/db PGVECTOR_USER=… PGVECTOR_PASSWORD=… mvn test -Dtest=PgVectorLiveTest}. */
@EnabledIfEnvironmentVariable(named = "PGVECTOR_URL", matches = "jdbc:postgresql:.+")
class PgVectorLiveTest extends PgVectorSearchIndexContract {
  @Override String url() { return System.getenv("PGVECTOR_URL"); }
  @Override String user() { return System.getenv("PGVECTOR_USER"); }
  @Override String password() { return System.getenv("PGVECTOR_PASSWORD"); }
}
