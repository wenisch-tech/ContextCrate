package tech.wenisch.contextcrate.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.domain.*;

/** The shared expectations against the in-process fake, plus what only a fake can check: the exact requests. */
class QdrantFakeServerTest extends QdrantSearchIndexContract {
  FakeQdrantServer server;

  @BeforeEach void start() throws IOException { server = new FakeQdrantServer(); }
  @AfterEach void stop() { server.close(); }

  @Override String endpoint() { return server.endpoint(); }
  @Override String apiKey() { return "secret-key"; }

  @Test
  void everyRequestCarriesTheApiKeyAndWritesWaitForCompletion() throws Exception {
    var doc = document("Handbook", "body");
    var c = chunk(doc, 0, "Customers receive an invoice by email.");
    index(new Topics(true)).upsert(doc, List.of(ChunkRetrievalRecord.standard(c)));

    assertThat(server.apiKeys).isNotEmpty().allMatch("secret-key"::equals);
    assertThat(server.requests).anyMatch(r -> r.startsWith("PUT /collections/") && r.endsWith("/points"));
    var points = server.collections.values().iterator().next().values();
    assertThat(points).hasSize(2);
    var chunkPoint = points.stream().filter(p -> p.payload().path("kind").asText().equals("chunk")).findFirst().orElseThrow();
    assertThat(chunkPoint.id()).isEqualTo(c.getId().toString());
    assertThat(chunkPoint.payload().path("chunk_id").asText()).isEqualTo(c.getId().toString());
    assertThat(chunkPoint.payload().path("source_text").asText()).isEqualTo(c.getContent());
    assertThat(chunkPoint.payload().path("record_type").asText()).isEqualTo("chunk");
    assertThat(chunkPoint.sparse()).isNotEmpty();
    assertThat(chunkPoint.dense()).containsExactly(0.0, 1.0, 0.0);
  }

  @Test
  void serverErrorsBecomeIOExceptionsWithStatusAndBody() throws Exception {
    var doc = document("Handbook", "body");
    server.failWith = 500;
    assertThatThrownBy(() -> index(new Topics(true)).upsert(doc, List.of())).isInstanceOf(IOException.class).hasMessageContaining("500").hasMessageContaining("boom");
  }
}
