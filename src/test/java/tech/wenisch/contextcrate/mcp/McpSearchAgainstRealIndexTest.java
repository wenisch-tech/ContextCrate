package tech.wenisch.contextcrate.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.wenisch.contextcrate.answer.AnswerService;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.Crate;
import tech.wenisch.contextcrate.domain.NormalizedDocument;
import tech.wenisch.contextcrate.embedding.DisabledEmbeddingProvider;
import tech.wenisch.contextcrate.index.ChunkRetrievalRecord;
import tech.wenisch.contextcrate.index.LuceneSearchIndex;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.repository.CrateRepository;
import tech.wenisch.contextcrate.repository.NormalizedDocumentRepository;
import tech.wenisch.contextcrate.service.SourceConfigurationCodec;
import tech.wenisch.contextcrate.service.SourceService;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exercises search_crate against a real Lucene index rather than a mocked one.
 *
 * <p>The whole justification for an in-process MCP tool is that {@code SearchHit.content} is
 * {@code @JsonIgnore}, so the REST endpoint can only ever return a ~240 character snippet. A mocked
 * index cannot demonstrate that, because the mock decides what comes back; this test indexes a chunk
 * for real and compares the two paths side by side.
 */
class McpSearchAgainstRealIndexTest {
  @TempDir Path temporary;

  private static final String CHUNK = """
      ContextCrate installs from a single jar. The installer requires Java 25 and at least two \
      gigabytes of heap. Configure the datasource with SPRING_DATASOURCE_URL, then set \
      CONTEXTCRATE_ADMIN_EMAIL and CONTEXTCRATE_ADMIN_PASSWORD before the first start. The \
      administrator password must be changed at the first sign-in, and the initial crate is \
      created automatically so that ingestion can begin immediately afterwards.""";

  private static NormalizedDocument document(UUID crateId) {
    var value = new NormalizedDocument(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        "https://docs.example.com/install", "Installation guide", null, null, null, CHUNK,
        tech.wenisch.contextcrate.storage.Hashing.sha256(CHUNK), "{}");
    value.assignCrate(crateId);
    return value;
  }

  @SuppressWarnings("unchecked")
  private static String text(Map<String, Object> result) {
    var content = (List<Map<String, Object>>) result.get("content");
    return String.valueOf(content.getFirst().get("text"));
  }

  @Test
  void theToolReturnsTheWholeChunkWhileTheRestPayloadWouldOnlyCarryASnippet() throws Exception {
    UUID crateId = UUID.randomUUID();
    Crate crate = new Crate(crateId, "Product docs", "Installation and operations", null);
    CrateRepository crates = mock(CrateRepository.class);
    when(crates.findById(crateId)).thenReturn(Optional.of(crate));
    var properties = new ContextCrateProperties("standalone", "all",
        new ContextCrateProperties.Queue("local"), new ContextCrateProperties.Database("h2"),
        null, new ContextCrateProperties.Index("lucene", temporary, null, "contextcrate"), null, null);

    try (var index = new LuceneSearchIndex(properties, new DisabledEmbeddingProvider(), crates)) {
      UUID chunkId = UUID.randomUUID();
      index.upsert(document(crateId), List.of(
          new ChunkRetrievalRecord(chunkId, chunkId, 0, "Installation", CHUNK, CHUNK,
              tech.wenisch.contextcrate.storage.Hashing.sha256(CHUNK), false)));

      var tools = new McpTools(index, mock(AnswerService.class), mock(NormalizedDocumentRepository.class),
          mock(SourceService.class), mock(SourceConfigurationCodec.class));
      Map<String, Object> result =
          tools.search(crate, JsonMapper.builder().build().readTree("{\"query\":\"installer\"}"));

      // What the model receives through MCP.
      String rendered = text(result);
      assertThat(rendered).contains(CHUNK);

      // What the same hit would look like over REST: content is @JsonIgnore, so only the snippet.
      SearchIndex.SearchHit hit = index
          .search(new SearchIndex.SearchRequest(crateId, "installer", 8, null, "chunk", "lexical"))
          .hits()
          .getFirst();
      String overRest = JsonMapper.builder().build().writeValueAsString(hit);
      assertThat(overRest).doesNotContain(CHUNK);
      assertThat(hit.snippet().length()).isLessThan(CHUNK.length());

      // The tail of the chunk falls outside the snippet window, so it is exactly the material a
      // REST client cannot see and an MCP client can.
      String tail = CHUNK.substring(CHUNK.length() - 120);
      assertThat(overRest).doesNotContain(tail);
      assertThat(rendered).contains(tail);
    }
  }

  @Test
  void anIndexedCrateWithNoMatchReportsThatPlainly() throws Exception {
    UUID crateId = UUID.randomUUID();
    Crate crate = new Crate(crateId, "Product docs", null, null);
    CrateRepository crates = mock(CrateRepository.class);
    when(crates.findById(crateId)).thenReturn(Optional.of(crate));
    var properties = new ContextCrateProperties("standalone", "all",
        new ContextCrateProperties.Queue("local"), new ContextCrateProperties.Database("h2"),
        null, new ContextCrateProperties.Index("lucene", temporary, null, "contextcrate"), null, null);

    try (var index = new LuceneSearchIndex(properties, new DisabledEmbeddingProvider(), crates)) {
      index.upsert(document(crateId), List.of());
      var tools = new McpTools(index, mock(AnswerService.class), mock(NormalizedDocumentRepository.class),
          mock(SourceService.class), mock(SourceConfigurationCodec.class));

      Map<String, Object> result = tools.search(crate,
          JsonMapper.builder().build().readTree("{\"query\":\"kubernetes\"}"));

      assertThat(text(result)).contains("No passages").contains("Product docs");
      assertThat(result.get("isError")).isEqualTo(false);
    }
  }
}
