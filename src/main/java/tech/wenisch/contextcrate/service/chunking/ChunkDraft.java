package tech.wenisch.contextcrate.service.chunking;

/** A chunk before it is persisted. {@code kind} is {@code text} or {@code code}. */
public record ChunkDraft(
    String heading, String content, String kind, String language, String symbol,
    Integer startLine, Integer endLine, int overlap) {
  /** A draft whose content does not repeat the end of the previous chunk. */
  public ChunkDraft(String heading, String content, String kind, String language, String symbol,
      Integer startLine, Integer endLine) {
    this(heading, content, kind, language, symbol, startLine, endLine, 0);
  }

  public static final String TEXT = "text";
  public static final String CODE = "code";

  public static ChunkDraft text(String heading, String content) {
    return new ChunkDraft(heading, content, TEXT, null, null, null, null);
  }
}
