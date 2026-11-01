package tech.wenisch.contextcrate.service.chunking;

import java.util.List;

/**
 * A structural unit of a document: one paragraph, list, table or code block, together with the
 * headings above it. Blocks are never cut unless they alone exceed the chunk size.
 */
public record Block(
    List<String> headingPath, Kind kind, String language, String text, ImageRef image) {
  public enum Kind { PROSE, CODE, IMAGE }

  public static Block prose(List<String> path, String text) {
    return new Block(List.copyOf(path), Kind.PROSE, null, text, null);
  }

  public static Block code(List<String> path, String language, String text) {
    return new Block(List.copyOf(path), Kind.CODE, language, text, null);
  }

  public static Block image(List<String> path, ImageRef image) {
    return new Block(List.copyOf(path), Kind.IMAGE, null, image.line(), image);
  }

  /** How the block appears in a document body or inside a mixed chunk: code keeps its fence. */
  public String rendered() {
    if (kind != Kind.CODE) return text;
    return "```" + (language == null ? "" : language) + "\n" + text + "\n```";
  }
}
