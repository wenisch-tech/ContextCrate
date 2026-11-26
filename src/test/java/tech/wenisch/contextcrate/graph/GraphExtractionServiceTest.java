package tech.wenisch.contextcrate.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.answer.AnswerGenerationProvider;
import tech.wenisch.contextcrate.config.RuntimeProviderSettings;
import tech.wenisch.contextcrate.domain.DocumentChunk;

class GraphExtractionServiceTest {
  private final UUID crate = UUID.randomUUID();
  private final AnswerGenerationProvider llm = mock(AnswerGenerationProvider.class);
  private final GraphStore store = mock(GraphStore.class);
  private final RuntimeProviderSettings providers = mock(RuntimeProviderSettings.class);
  private final GraphExtractionService service = new GraphExtractionService(llm, store, providers);

  GraphExtractionServiceTest() {
    when(providers.effectiveAnswer()).thenReturn(new RuntimeProviderSettings.Answering(true, "http://llm", "m", null, null, 30, 0, 100));
    when(llm.available()).thenReturn(true);
  }

  private static DocumentChunk chunk(String text) {
    return new DocumentChunk(UUID.randomUUID(), UUID.randomUUID(), 0, "Intro", text, "hash-" + text.hashCode());
  }

  @Test
  void parsesEntitiesAndRelationsAndDropsInvalidOnes() throws Exception {
    var result = service.parse("```json\n{\"entities\":[{\"name\":\"PostgreSQL\",\"type\":\"database system\",\"description\":\"Stores data.\"},"
        + "{\"name\":\"postgresql\",\"type\":\"X\"},{\"name\":\" \"},{\"name\":\"ContextCrate\",\"type\":\"product\"}],"
        + "\"relations\":[{\"source\":\"ContextCrate\",\"target\":\"PostgreSQL\",\"description\":\"uses\",\"strength\":42},"
        + "{\"source\":\"PostgreSQL\",\"target\":\"ContextCrate\"},{\"source\":\"ContextCrate\",\"target\":\"Unknown\"},{\"source\":\"ContextCrate\",\"target\":\"contextcrate\"}]}\n```",
        UUID.randomUUID(), UUID.randomUUID());
    assertThat(result.entities()).extracting(GraphModel.Mention::name).containsExactly("PostgreSQL", "ContextCrate");
    assertThat(result.entities().getFirst().type()).isEqualTo("DATABASE_SYSTEM");
    assertThat(result.relations()).hasSize(1);
    assertThat(result.relations().getFirst().strength()).isEqualTo(10);
  }

  @Test
  void rejectsNonJson() {
    assertThatThrownBy(() -> service.parse("no graph here", UUID.randomUUID(), UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void storesNewExtractionsAndMarksTheGraphDirty() throws Exception {
    when(store.extractionFingerprint(any())).thenReturn(Optional.empty());
    when(llm.complete(any(), anyDouble())).thenReturn("{\"entities\":[{\"name\":\"Alpha\",\"type\":\"PRODUCT\",\"description\":\"d\"}],\"relations\":[]}");
    var c = chunk("Alpha is a product that is described here in enough words.");
    service.extract(crate, List.of(c));
    verify(store).saveExtraction(eq(crate), eq(c.getId()), eq(c.getDocumentId()), anyString(), eq("m"), argThat(l -> l.size() == 1), eq(List.of()));
    verify(store).markDirty(crate);
  }

  @Test
  void cachedChunksAreNotSentToTheModel() throws Exception {
    var c = chunk("Alpha is a product that is described here in enough words.");
    String fingerprint = GraphExtractionService.fingerprint(c, "http://llm", "m");
    when(store.extractionFingerprint(c.getId())).thenReturn(Optional.of(fingerprint));
    service.extract(crate, List.of(c));
    verify(llm, never()).complete(any(), anyDouble());
    verify(store, never()).saveExtraction(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void modelFailuresAndShortChunksAreFailOpen() throws Exception {
    when(store.extractionFingerprint(any())).thenReturn(Optional.empty());
    when(llm.complete(any(), anyDouble())).thenThrow(new RuntimeException("down"));
    service.extract(crate, List.of(chunk("Alpha is a product that is described here in enough words."), chunk("short")));
    verify(llm, times(1)).complete(any(), anyDouble());
    verify(store, never()).saveExtraction(any(), any(), any(), any(), any(), any(), any());
    verify(store).markDirty(crate);
  }
}
