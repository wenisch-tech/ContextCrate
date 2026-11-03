package tech.wenisch.contextcrate.answer;

import java.util.ArrayList;
import java.util.List;
import tech.wenisch.contextcrate.domain.DocumentChunk;

/**
 * Joins consecutive chunks of one document back into continuous text.
 *
 * <p>Prose that was cut inside a long paragraph is cut with an overlap: a chunk starts with the last
 * characters of the previous one. The number of repeated characters is stored with the chunk when it
 * is created, so dropping exactly those characters restores the original text without repetition. Chunks that
 * were cut from different blocks do not overlap and are separated by a blank line, as they were in
 * the source; a chunk that continues the same paragraph without repeating anything (stored as -1) is
 * joined with a single space. Chunks created before the overlap was recorded fall back to detecting a repetition of
 * at least {@value #LEGACY_MIN_OVERLAP} characters, so a coincidental match is never cut.
 */
public final class ChunkJoiner {
  public static final int LEGACY_MIN_OVERLAP = 20;
  static final String SEPARATOR = "\n\n";

  private ChunkJoiner() {}

  /** Joins raw chunk contents in the order given. */
  public static String joinText(List<DocumentChunk> chunks) {
    StringBuilder out = new StringBuilder();
    DocumentChunk previous = null;
    for (DocumentChunk chunk : chunks) {
      out.append(previous == null ? chunk.getContent() : continuation(out.toString(), chunk));
      previous = chunk;
    }
    return out.toString();
  }

  /**
   * The text to append to {@code before} for {@code next}: the chunk without its repeated start, or
   * the chunk preceded by a blank line when it does not overlap.
   */
  static String continuation(String before, DocumentChunk next) {
    String content = next.getContent();
    Integer stored = next.getOverlapChars();
    // Cut from the same paragraph without a repeated start: the original separator was a single space.
    if (stored != null && stored < 0) return " " + content;
    int overlap = overlapOf(before, next);
    return overlap > 0 ? content.substring(overlap) : SEPARATOR + content;
  }

  /** Verified number of leading characters of {@code next} that repeat the end of {@code before}. */
  static int overlapOf(String before, DocumentChunk next) {
    String content = next.getContent();
    Integer stored = next.getOverlapChars();
    if (stored != null) {
      return stored > 0 && stored <= content.length() && before.endsWith(content.substring(0, stored)) ? stored : 0;
    }
    int max = Math.min(before.length(), content.length());
    for (int k = max; k >= LEGACY_MIN_OVERLAP; k--) if (before.endsWith(content.substring(0, k))) return k;
    return 0;
  }

  /**
   * Renders consecutive chunks for a model or a person: prose as it is, code chunks of the same language
   * together inside one fenced block.
   */
  public static String render(List<DocumentChunk> chunks) {
    List<String> segments = new ArrayList<>();
    List<DocumentChunk> run = new ArrayList<>();
    for (DocumentChunk chunk : chunks) {
      if (!run.isEmpty() && !sameKind(run.get(run.size() - 1), chunk)) {
        segments.add(segment(run));
        run.clear();
      }
      run.add(chunk);
    }
    if (!run.isEmpty()) segments.add(segment(run));
    return String.join(SEPARATOR, segments);
  }

  private static boolean sameKind(DocumentChunk a, DocumentChunk b) {
    if (a.isCode() != b.isCode()) return false;
    return !a.isCode() || java.util.Objects.equals(a.getLanguage(), b.getLanguage());
  }

  private static String segment(List<DocumentChunk> run) {
    String text = joinText(run);
    DocumentChunk first = run.get(0);
    return first.isCode() ? "```" + (first.getLanguage() == null ? "" : first.getLanguage()) + "\n" + text + "\n```" : text;
  }
}
