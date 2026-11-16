package tech.wenisch.contextcrate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tech.wenisch.contextcrate.answer.AnswerGenerationProvider;
import tech.wenisch.contextcrate.config.RuntimeProviderSettings;
import tech.wenisch.contextcrate.domain.ChunkCaption;
import tech.wenisch.contextcrate.domain.DocumentChunk;
import tech.wenisch.contextcrate.repository.ChunkCaptionRepository;
import tech.wenisch.contextcrate.storage.Hashing;

class CaptionServiceTest {
  private final UUID crateId = UUID.randomUUID();
  private final AnswerGenerationProvider llm = mock(AnswerGenerationProvider.class);
  private final ChunkCaptionRepository repository = mock(ChunkCaptionRepository.class);
  private final RuntimeProviderSettings providers = mock(RuntimeProviderSettings.class);
  private final List<ChunkCaption> stored = new ArrayList<>();
  private final CaptionService service = new CaptionService(llm, repository, providers);

  CaptionServiceTest() throws Exception {
    when(providers.effectiveAnswer()).thenReturn(new RuntimeProviderSettings.Answering(true, "http://llm", "model-a", "", "", 30, 0.2, 800));
    when(llm.available()).thenReturn(true);
    when(llm.model()).thenReturn("model-a");
    when(repository.findByChunkId(any())).thenAnswer(i -> stored.stream().filter(c -> c.getChunkId().equals(i.getArgument(0))).findFirst());
    doAnswer(i -> { stored.removeIf(c -> c.getChunkId().equals(i.getArgument(0))); return null; }).when(repository).deleteByChunkId(any());
    when(repository.save(any())).thenAnswer(i -> { stored.add(i.getArgument(0)); return i.getArgument(0); });
  }

  private DocumentChunk chunk(String text, String kind, String language) {
    var c = new DocumentChunk(UUID.randomUUID(), UUID.randomUUID(), 0, "Pricing", text, Hashing.sha256(text));
    c.assignCrate(crateId);
    if (kind != null) c.describe(kind, language, null, null, null);
    return c;
  }

  private static final String TABLE = "Plan | Price\nFree | 0\nPro | 20";
  private static final String MERMAID = "graph LR\n  A[Crawler] --> B[Parser]\n  B --> C[Index]";

  @Test
  void kindOfRecognizesTablesAndDiagramCodeOnly() {
    assertThat(CaptionService.kindOf(chunk(TABLE, null, null))).isEqualTo("table");
    assertThat(CaptionService.kindOf(chunk(MERMAID, "code", "mermaid"))).isEqualTo("diagram");
    assertThat(CaptionService.kindOf(chunk("digraph { a -> b }", "code", "DOT"))).isEqualTo("diagram");
    assertThat(CaptionService.kindOf(chunk("print('x')", "code", "python"))).isNull();
    assertThat(CaptionService.kindOf(chunk("Plain prose only.", null, null))).isNull();
    assertThat(CaptionService.kindOf(chunk(TABLE, "image", null))).isNull();
  }

  @Test
  void captionsTablesAndDiagramsAndSkipsOtherChunks() throws Exception {
    when(llm.complete(any(), anyDouble())).thenReturn("```json\n{\"caption\":\"Compares plan prices.\"}\n```");
    var table = chunk(TABLE, null, null);
    var diagram = chunk(MERMAID, "code", "mermaid");
    var prose = chunk("Just a paragraph of prose.", null, null);

    var result = service.captionsFor(crateId, List.of(table, diagram, prose));

    assertThat(result).containsOnlyKeys(table.getId(), diagram.getId());
    assertThat(result.get(table.getId())).isEqualTo(new CaptionService.Caption("table", "Compares plan prices."));
    assertThat(result.get(diagram.getId()).kind()).isEqualTo("diagram");
    assertThat(stored).hasSize(2);
    var messages = ArgumentCaptor.forClass(List.class);
    verify(llm, times(2)).complete(messages.capture(), anyDouble());
    assertThat(messages.getAllValues().toString()).contains("untrusted data").contains("Section: Pricing");
  }

  @Test
  void aSecondRunIsServedFromTheCacheAndAChangedChunkRegenerates() throws Exception {
    when(llm.complete(any(), anyDouble())).thenReturn("{\"caption\":\"First.\"}");
    var table = chunk(TABLE, null, null);

    service.captionsFor(crateId, List.of(table));
    var again = service.captionsFor(crateId, List.of(table));
    verify(llm, times(1)).complete(any(), anyDouble());
    assertThat(again.get(table.getId()).text()).isEqualTo("First.");

    var changed = new DocumentChunk(table.getId(), table.getDocumentId(), 0, "Pricing", TABLE + "\nTeam | 50", Hashing.sha256("changed"));
    changed.assignCrate(crateId);
    when(llm.complete(any(), anyDouble())).thenReturn("{\"caption\":\"Second.\"}");
    assertThat(service.captionsFor(crateId, List.of(changed)).get(table.getId()).text()).isEqualTo("Second.");
    assertThat(stored).singleElement().satisfies(c -> assertThat(c.getCaption()).isEqualTo("Second."));
  }

  @Test
  void failuresAreFailOpenAndNotCached() throws Exception {
    when(llm.complete(any(), anyDouble())).thenThrow(new IllegalStateException("down"));
    var table = chunk(TABLE, null, null);

    assertThat(service.captionsFor(crateId, List.of(table))).isEmpty();
    assertThat(stored).isEmpty();

    doReturn("{\"nothing\":1}").when(llm).complete(any(), anyDouble());
    assertThat(service.captionsFor(crateId, List.of(table))).isEmpty();

    when(llm.available()).thenReturn(false);
    assertThat(service.captionsFor(crateId, List.of(table))).isEmpty();
    assertThat(stored).isEmpty();
  }

  @Test
  void parsesLenientlyAndCapsTheLength() {
    assertThat(service.parse("{\"caption\":\" Two   words \"}")).isEqualTo("Two words");
    assertThat(service.parse("A plain reply.")).isEqualTo("A plain reply.");
    assertThat(service.parse("")).isNull();
    assertThat(service.parse("{\"caption\":\"\"}")).isNull();
    assertThat(service.parse("{\"caption\":\"" + "x".repeat(2000) + "\"}")).hasSize(CaptionService.MAX_CAPTION);
  }
}
