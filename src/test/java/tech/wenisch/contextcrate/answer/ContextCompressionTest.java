package tech.wenisch.contextcrate.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.service.chunking.SentenceSplitter;

class ContextCompressionTest {
  private final UUID crateId = UUID.randomUUID();
  private final AnswerGenerationProvider provider = mock(AnswerGenerationProvider.class);
  private final ContextCompressionService service = new ContextCompressionService(provider);

  private AnswerService.Source source(int n, String content) {
    return new AnswerService.Source(n, UUID.nameUUIDFromBytes(("c" + n).getBytes()), UUID.randomUUID(), UUID.randomUUID(),
        "T", "https://example.test/" + n, n, "snippet", 1f, content);
  }

  /** Replies per call by looking at which sentence the excerpt contains. */
  private void reply(String contains, String json) throws Exception {
    when(provider.complete(argThat(m -> m != null && m.get(1).content().contains(contains)))).thenReturn(json);
  }

  @Test
  void splitsSentencesAndJoinsThemKeepingParagraphs() {
    var units = SentenceSplitter.split("First one. Second one.\n\nNew paragraph here. Last.");
    assertThat(units).extracting(SentenceSplitter.Unit::text).containsExactly("First one.", "Second one.", "New paragraph here.", "Last.");
    assertThat(units).extracting(SentenceSplitter.Unit::paragraphStart).containsExactly(true, false, true, false);
    assertThat(SentenceSplitter.join(units)).isEqualTo("First one. Second one.\n\nNew paragraph here. Last.");
    assertThat(SentenceSplitter.join(List.of(units.get(0), units.get(3)))).isEqualTo("First one. Last.");
  }

  @Test
  void parsesTheSelectionDefensively() {
    assertThat(ContextCompressionService.parse("{\"relevant\":[1,3,3,9]}", 4)).containsExactly(1, 3);
    assertThat(ContextCompressionService.parse("Sure! {\"relevant\":[\"2\"]} done", 4)).containsExactly(2);
    assertThat(ContextCompressionService.parse("{\"relevant\":[]}", 4)).isEmpty();
    assertThat(ContextCompressionService.parse("nothing", 4)).isNull();
    assertThat(ContextCompressionService.parse("{\"other\":1}", 4)).isNull();
  }

  @Test
  void keepsOnlySelectedSentencesDropsIrrelevantChunksAndRemovesDuplicates() throws Exception {
    when(provider.available()).thenReturn(true);
    reply("Alpha", "{\"relevant\":[2]}");        // keeps "The rotation is weekly."
    reply("Beta", "{\"relevant\":[]}");          // nothing relevant: the chunk is dropped
    reply("Gamma", "{\"relevant\":[1,2]}");      // second sentence repeats chunk 1
    var sources = List.of(
        source(1, "Alpha intro sentence. The rotation is weekly. Unrelated closing words."),
        source(2, "Beta talks about lunch. Lunch is served at noon."),
        source(3, "The rotation is weekly. Gamma pages the secondary after five minutes."));

    var result = service.compress(crateId, "How does on-call work?", sources);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).content()).isEqualTo("The rotation is weekly.");
    assertThat(result.get(0).citation()).isEqualTo(1);
    assertThat(result.get(1).content()).isEqualTo("Gamma pages the secondary after five minutes.");
    assertThat(result.get(1).citation()).isEqualTo(2);
    assertThat(result.get(1).chunkId()).isEqualTo(sources.get(2).chunkId());
  }

  @Test
  void failuresAndCodeKeepTheChunkUnchanged() throws Exception {
    when(provider.available()).thenReturn(true);
    reply("Broken", "this is not json");
    reply("Boom", "{\"relevant\":[1]}");
    when(provider.complete(argThat(m -> m != null && m.get(1).content().contains("Throws")))).thenThrow(new IllegalStateException("down"));
    var sources = List.of(
        source(1, "Broken reply. Still kept."),
        source(2, "Throws here. Kept as well."),
        source(3, "Look:\n```java\nint x = 1;\n```\nDone."));

    var result = service.compress(crateId, "q", sources);

    assertThat(result).extracting(AnswerService.Source::content).containsExactly(
        "Broken reply. Still kept.", "Throws here. Kept as well.", "Look:\n```java\nint x = 1;\n```\nDone.");
    verify(provider, never()).complete(argThat(m -> m != null && m.get(1).content().contains("int x")));
  }

  @Test
  void anUnavailableModelLeavesEverythingAsItIs() throws Exception {
    when(provider.available()).thenReturn(false);
    var sources = List.of(source(1, "One. Two."));
    assertThat(service.compress(crateId, "q", sources)).isEqualTo(sources);
    verify(provider, never()).complete(any());
  }
}
