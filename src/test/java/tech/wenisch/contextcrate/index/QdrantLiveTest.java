package tech.wenisch.contextcrate.index;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Runs the shared expectations against a real Qdrant: {@code QDRANT_URL=http://localhost:6333 mvn test -Dtest=QdrantLiveTest}. */
@EnabledIfEnvironmentVariable(named = "QDRANT_URL", matches = "https?://.+")
class QdrantLiveTest extends QdrantSearchIndexContract {
  @Override String endpoint() { return System.getenv("QDRANT_URL"); }
  @Override String apiKey() { return System.getenv("QDRANT_API_KEY"); }
}
