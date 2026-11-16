package tech.wenisch.contextcrate.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.Crate;
import tech.wenisch.contextcrate.domain.DocumentChunk;
import tech.wenisch.contextcrate.domain.NormalizedDocument;
import tech.wenisch.contextcrate.embedding.EmbeddingProvider;
import tech.wenisch.contextcrate.repository.CrateRepository;
import tech.wenisch.contextcrate.storage.Hashing;

class LuceneHierarchicalSearchTest {
  @TempDir Path temporary;

  private static DocumentChunk chunk(NormalizedDocument d, UUID crateId, int ordinal, String heading, String text) {
    var c = new DocumentChunk(UUID.nameUUIDFromBytes(("c" + ordinal).getBytes()), d.getId(), ordinal, heading, text, Hashing.sha256(text));
    c.assignCrate(crateId);
    return c;
  }

  @Test
  void summaryRowsAreSeparateFromChunksAndChunksCanBeRestrictedToTheirIds() throws Exception {
    UUID crateId = UUID.randomUUID();
    CrateRepository crates = mock(CrateRepository.class);
    when(crates.findById(crateId)).thenReturn(Optional.of(new Crate(crateId, "Hier", null, null)));
    EmbeddingProvider embeddings = mock(EmbeddingProvider.class);
    when(embeddings.available()).thenReturn(false);
    var properties = new ContextCrateProperties("standalone", "all", new ContextCrateProperties.Queue("local"),
        new ContextCrateProperties.Database("h2"), null, new ContextCrateProperties.Index("lucene", temporary, null, "contextcrate"), null, null);
    try (var index = new LuceneSearchIndex(properties, embeddings, crates)) {
      var document = new NormalizedDocument(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "https://example.test/a",
          "a.md", null, null, null, "body", Hashing.sha256("body"), "{}");
      document.assignCrate(crateId);
      var first = chunk(document, crateId, 0, "Ops > Pager", "rotation of the pager duty");
      var second = chunk(document, crateId, 1, "Billing > Invoices", "rotation of invoice numbers");
      UUID sectionId = UUID.randomUUID();
      var summary = ChunkRetrievalRecord.summary(sectionId, 0, "Ops > Pager", "Section: Ops > Pager\nWho is on call and how rotation works.");

      index.upsert(document, List.of(ChunkRetrievalRecord.standard(first), ChunkRetrievalRecord.standard(second), summary));

      // The summary level is only returned when asked for, and a hit's id is the section id.
      var summaries = index.search(new SearchIndex.SearchRequest(crateId, "rotation", 5, null, "summary", "lexical")).hits();
      assertThat(summaries).singleElement().satisfies(h -> {
        assertThat(h.id()).isEqualTo(sectionId);
        assertThat(h.kind()).isEqualTo("summary");
        assertThat(h.chunkOrdinal()).isEqualTo(0);
      });
      assertThat(index.search(new SearchIndex.SearchRequest(crateId, "rotation", 5, null, "chunk", "lexical")).hits())
          .extracting(SearchIndex.SearchHit::id).containsExactlyInAnyOrder(first.getId(), second.getId());
      assertThat(index.search(new SearchIndex.SearchRequest(crateId, "rotation", 5, null, null, "lexical")).hits())
          .extracting(SearchIndex.SearchHit::kind).doesNotContain("summary");

      // Restricting the chunk search to chunk ids narrows it; an empty set matches nothing.
      var narrowed = new SearchIndex.SearchRequest(crateId, "rotation", 5, null, "chunk", "lexical").withChunkIds(Set.of(second.getId()));
      assertThat(index.search(narrowed).hits()).extracting(SearchIndex.SearchHit::id).containsExactly(second.getId());
      assertThat(index.search(narrowed.withChunkIds(Set.of())).hits()).isEmpty();
    }
  }
}
