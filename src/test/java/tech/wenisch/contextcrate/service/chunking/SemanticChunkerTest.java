package tech.wenisch.contextcrate.service.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.answer.ChunkJoiner;
import tech.wenisch.contextcrate.domain.CrawlConfiguration.SemanticChunking;
import tech.wenisch.contextcrate.domain.DocumentChunk;

class SemanticChunkerTest {
  /** Topic by keyword: each topic is its own axis, so a topic change is a distance of 1. */
  static final SemanticChunker.SentenceEmbedder TOPICS = sentences -> {
    List<float[]> out = new ArrayList<>();
    for (String s : sentences) {
      float[] v = new float[3];
      v[s.contains("cat") ? 0 : s.contains("stock") ? 1 : 2] = 1;
      out.add(v);
    }
    return out;
  };

  static final SemanticChunking P90 = new SemanticChunking(true, "percentile", 80, 0).normalized(1000);

  static Block prose(String... text) { return Block.prose(List.of("Doc"), String.join(" ", text)); }

  static String text(String topic, int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) sb.append("The ").append(topic).append(" sentence number ").append(i).append(" is here. ");
    return sb.toString().strip();
  }

  @Test
  void cutsWhereTheTopicChanges() {
    var blocks = List.of(prose(text("cat", 4), text("stock", 4), text("rocket", 4)));
    var result = SemanticChunker.pack(blocks, new ChunkOptions(1000, 100), P90, TOPICS);

    assertThat(result.stats().applied()).isTrue();
    assertThat(result.stats().breakpoints()).isEqualTo(2);
    assertThat(result.drafts()).hasSize(3);
    assertThat(result.drafts().get(0).content()).contains("cat").doesNotContain("stock");
    assertThat(result.drafts().get(1).content()).contains("stock").doesNotContain("cat", "rocket");
    assertThat(result.drafts().get(2).content()).contains("rocket");
    assertThat(result.drafts()).allMatch(d -> d.heading().equals("Doc"));
  }

  @Test
  void headingsAreNeverCrossedEvenWithSameTopic() {
    var blocks = List.of(Block.prose(List.of("A"), text("cat", 3)), Block.prose(List.of("B"), text("cat", 3)));
    var drafts = SemanticChunker.pack(blocks, new ChunkOptions(1000, 100), P90, TOPICS).drafts();
    assertThat(drafts).extracting(ChunkDraft::heading).containsExactly("A", "B");
  }

  @Test
  void codeKeepsItsPositionAndIsNotSentenceSplit() {
    var blocks = List.of(prose(text("cat", 3), text("stock", 3)),
        Block.code(List.of("Doc"), "java", "int x = 1; // cat. stock. rocket."),
        prose(text("rocket", 3), text("cat", 3)));
    var drafts = SemanticChunker.pack(blocks, new ChunkOptions(1000, 100), P90, TOPICS).drafts();
    int code = -1;
    for (int i = 0; i < drafts.size(); i++) if (ChunkDraft.CODE.equals(drafts.get(i).kind())) code = i;
    assertThat(code).isGreaterThan(0).isLessThan(drafts.size() - 1);
    assertThat(drafts.get(code).content()).isEqualTo("int x = 1; // cat. stock. rocket.");
  }

  @Test
  void groupsNeverExceedTheChunkSize() {
    var blocks = List.of(prose(text("cat", 30)));
    var drafts = SemanticChunker.pack(blocks, new ChunkOptions(300, 0), P90, (SemanticChunker.SentenceEmbedder) s -> {
      // gently drifting vectors so there is something to split on
      List<float[]> out = new ArrayList<>();
      for (int i = 0; i < s.size(); i++) out.add(new float[] {(float) Math.cos(i * 0.2), (float) Math.sin(i * 0.2), 0});
      return out;
    }).drafts();
    assertThat(drafts).hasSizeGreaterThan(1);
    assertThat(drafts).allMatch(d -> d.content().length() <= 300);
  }

  @Test
  void tinyGroupsAreMergedIntoTheirCloserNeighbour() {
    SemanticChunking min = new SemanticChunking(true, "percentile", 80, 150).normalized(1000);
    var blocks = List.of(prose(text("cat", 5), "A stock tip.", text("rocket", 5)));
    var result = SemanticChunker.pack(blocks, new ChunkOptions(1000, 100), min, TOPICS);
    assertThat(result.drafts()).allMatch(d -> d.content().length() >= 150);
    assertThat(result.drafts().stream().map(ChunkDraft::content).reduce("", String::concat)).contains("A stock tip.");
  }

  @Test
  void joinedChunksGiveBackTheParagraphs() {
    String first = text("cat", 3) + " " + text("stock", 3);
    String second = text("rocket", 3);
    var blocks = List.of(Block.prose(List.of("Doc"), first), Block.prose(List.of("Doc"), second));
    var drafts = SemanticChunker.pack(blocks, new ChunkOptions(1000, 100), P90, TOPICS).drafts();
    List<DocumentChunk> chunks = new ArrayList<>();
    int ordinal = 0;
    for (ChunkDraft d : drafts) {
      var c = new DocumentChunk(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), ordinal++,
          d.heading(), d.content(), "h");
      c.describe(d.kind(), d.language(), d.symbol(), d.startLine(), d.endLine());
      c.overlap(d.overlap());
      chunks.add(c);
    }
    assertThat(ChunkJoiner.joinText(chunks)).isEqualTo(first + "\n\n" + second);
  }

  @Test
  void failuresFallBackToStandardChunking() {
    var blocks = List.of(prose(text("cat", 4), text("stock", 4)));
    var options = new ChunkOptions(1000, 100);
    var result = SemanticChunker.pack(blocks, options, P90, s -> { throw new IllegalStateException("down"); });
    assertThat(result.stats().applied()).isFalse();
    assertThat(result.stats().fallback()).isEqualTo("error");
    assertThat(result.drafts()).isEqualTo(BlockPacker.pack(blocks, options));
  }

  @Test
  void tooManySentencesFallBackWithoutEmbedding() {
    var blocks = List.of(prose(text("cat", SemanticChunker.MAX_SENTENCES + 1)));
    boolean[] called = {false};
    var result = SemanticChunker.pack(blocks, new ChunkOptions(1000, 100), P90, s -> { called[0] = true; return null; });
    assertThat(result.stats().fallback()).isEqualTo("too_many_sentences");
    assertThat(called[0]).isFalse();
  }
}
