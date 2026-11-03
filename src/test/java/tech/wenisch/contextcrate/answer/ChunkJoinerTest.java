package tech.wenisch.contextcrate.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.domain.DocumentChunk;
import tech.wenisch.contextcrate.service.chunking.TextSplitter;
import tech.wenisch.contextcrate.storage.Hashing;

class ChunkJoinerTest {
  private final UUID document = UUID.randomUUID();

  private DocumentChunk chunk(int ordinal, String content, Integer overlap) {
    return new DocumentChunk(UUID.randomUUID(), document, ordinal, null, content, Hashing.sha256(content)).overlap(overlap);
  }

  private List<DocumentChunk> chunksOf(String text, int size, int overlap) {
    List<DocumentChunk> chunks = new ArrayList<>();
    for (var part : TextSplitter.proseParts(text, size, overlap)) chunks.add(chunk(chunks.size(), part.text(), part.overlap()));
    return chunks;
  }

  private static String text(Random random, int words) {
    String[] vocabulary = {"alpha", "beta", "gamma", "delta", "on-call", "escalation", "rotation", "pager", "primary", "secondary", "a", "of", "the"};
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < words; i++) {
      if (i > 0) out.append(random.nextInt(25) == 0 ? "\n" : " ");
      out.append(vocabulary[random.nextInt(vocabulary.length)]);
    }
    return out.toString();
  }

  @Test
  void splittingWithOverlapAndJoiningRestoresTheOriginalTextForManySizes() {
    Random random = new Random(42);
    for (int run = 0; run < 300; run++) {
      String original = text(random, 80 + random.nextInt(400));
      int size = 80 + random.nextInt(300);
      int overlap = random.nextInt(size / 2);
      var chunks = chunksOf(original, size, overlap);

      String joined = ChunkJoiner.joinText(chunks);

      // Whitespace between pieces that was not part of any piece (overlap 0) becomes one space; compare normalised.
      assertThat(normalise(joined)).as("size=%d overlap=%d", size, overlap).isEqualTo(normalise(original));
      if (overlap > 0) assertThat(joined).as("exact when chunks overlap").isEqualTo(original.trim());
    }
  }

  private static String normalise(String text) { return text.trim().replaceAll("\\s+", " "); }

  @Test
  void theStoredOverlapIsExactAndOnlyOnContinuationPieces() {
    String original = "The quick brown fox jumps over the lazy dog and keeps running through the forest until night falls over everything.";
    var parts = TextSplitter.proseParts(original, 50, 15);

    assertThat(parts.get(0).overlap()).isZero();
    for (int i = 1; i < parts.size(); i++) {
      int k = parts.get(i).overlap();
      assertThat(k).isPositive().isLessThanOrEqualTo(15);
      assertThat(parts.get(i - 1).text()).endsWith(parts.get(i).text().substring(0, k));
    }
  }

  @Test
  void noOverlapInProseIsMarkedAsContinuation() {
    var parts = TextSplitter.proseParts("one two three four five six seven eight nine ten eleven twelve", 20, 0);
    assertThat(parts).hasSizeGreaterThan(1);
    assertThat(parts.get(0).overlap()).isZero();
    assertThat(parts.subList(1, parts.size())).allMatch(p -> p.overlap() == TextSplitter.CONTINUES);
    assertThat(ChunkJoiner.joinText(chunksOf("one two three four five six seven eight nine ten eleven twelve", 20, 0)))
        .isEqualTo("one two three four five six seven eight nine ten eleven twelve");
  }

  @Test
  void chunksFromDifferentBlocksAreSeparatedByABlankLine() {
    var chunks = List.of(chunk(0, "First block ends with the same words as", 0), chunk(1, "the same words as the second block starts with", 0));

    // An accidental repetition is not cut when the overlap is known to be zero.
    assertThat(ChunkJoiner.joinText(chunks)).isEqualTo("First block ends with the same words as\n\nthe same words as the second block starts with");
  }

  @Test
  void aStoredOverlapThatDoesNotMatchIsIgnoredInsteadOfDeletingText() {
    var chunks = List.of(chunk(0, "alpha beta gamma", 0), chunk(1, "delta epsilon zeta", 8));

    assertThat(ChunkJoiner.joinText(chunks)).isEqualTo("alpha beta gamma\n\ndelta epsilon zeta");
  }

  @Test
  void chunksCreatedBeforeTheOverlapWasRecordedAreDetectedWithAMinimumLength() {
    String shared = "this is a repeated stretch of text"; // 34 characters
    var overlapping = List.of(chunk(0, "start of the passage and " + shared, null), chunk(1, shared + " then it goes on", null));
    assertThat(ChunkJoiner.joinText(overlapping)).isEqualTo("start of the passage and " + shared + " then it goes on");

    // A coincidence of a few characters is not an overlap.
    var coincidence = List.of(chunk(0, "ends with the", null), chunk(1, "the beginning of another chunk", null));
    assertThat(ChunkJoiner.joinText(coincidence)).isEqualTo("ends with the\n\nthe beginning of another chunk");
  }

  @Test
  void codeChunksOfOneLanguageShareOneFence() {
    var a = chunk(0, "int a = 1;", 0).describe("code", "java", null, null, null);
    var b = chunk(1, "int b = 2;", 0).describe("code", "java", null, null, null);
    var prose = chunk(2, "After the code.", 0);

    assertThat(ChunkJoiner.render(List.of(a, b, prose))).isEqualTo("```java\nint a = 1;\n\nint b = 2;\n```\n\nAfter the code.");
  }
}
