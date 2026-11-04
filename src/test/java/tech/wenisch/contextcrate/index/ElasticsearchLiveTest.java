package tech.wenisch.contextcrate.index;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Runs the shared expectations against a real Elasticsearch: {@code ELASTICSEARCH_URL=http://localhost:9200 mvn test -Dtest=ElasticsearchLiveTest}. */
@EnabledIfEnvironmentVariable(named = "ELASTICSEARCH_URL", matches = "https?://.+")
class ElasticsearchLiveTest extends ElasticsearchSearchIndexContract {
  @Override String endpoint() { return System.getenv("ELASTICSEARCH_URL"); }
  @Override String apiKey() { return System.getenv("ELASTICSEARCH_API_KEY"); }
  @Override String username() { return System.getenv("ELASTICSEARCH_USERNAME"); }
  @Override String password() { return System.getenv("ELASTICSEARCH_PASSWORD"); }
}
