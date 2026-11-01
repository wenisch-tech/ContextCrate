package tech.wenisch.contextcrate.service.chunking;

/** A chunk before it is persisted. {@code kind} is {@code text} or {@code code}. */
public record ChunkDraft(
    String heading, String content, String kind, String language, String symbol,
    Integer startLine, Integer endLine) {
  public static final String TEXT = "text";
  public static final String CODE = "code";

  public static ChunkDraft text(String heading, String content) {
    return new ChunkDraft(heading, content, TEXT, null, null, null, null);
  }
}
