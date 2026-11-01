package tech.wenisch.contextcrate.service.chunking;

import java.util.ArrayList;
import java.util.List;

/** Splits text that is too large for one chunk, on the most natural seam available. */
public final class TextSplitter {
  private TextSplitter() {}

  /** Prose: cut at a space or newline in the second half of the window, with overlap. */
  public static List<String> prose(String text, int size, int overlap) {
    List<String> parts = new ArrayList<>();
    int start = 0;
    while (start < text.length()) {
      int end = Math.min(text.length(), start + size);
      if (end < text.length()) {
        int boundary = Math.max(text.lastIndexOf(' ', end), text.lastIndexOf('\n', end));
        if (boundary > start + size / 2) end = boundary;
      }
      String part = text.substring(start, end).trim();
      if (!part.isBlank()) parts.add(part);
      if (end >= text.length()) break;
      start = Math.max(start + 1, end - overlap);
    }
    return parts;
  }

  /**
   * Code: always cut between lines, preferring a blank line close to the limit, so no statement is
   * split mid-line. A single line longer than {@code size} is hard-wrapped as a last resort.
   */
  public static List<String> lines(String text, int size) {
    List<String> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int lastBlank = -1; // length of `current` just after the last blank line
    for (String line : text.split("\n", -1)) {
      while (line.length() > size) { // pathological minified line
        flush(parts, current);
        lastBlank = -1;
        parts.add(line.substring(0, size));
        line = line.substring(size);
      }
      if (current.length() + line.length() + 1 > size && current.length() > 0) {
        if (lastBlank > size / 2) {
          String head = current.substring(0, lastBlank);
          String tail = current.substring(lastBlank);
          add(parts, head);
          current = new StringBuilder(tail);
        } else {
          flush(parts, current);
        }
        lastBlank = -1;
      }
      current.append(line).append('\n');
      if (line.isBlank()) lastBlank = current.length();
    }
    flush(parts, current);
    return parts;
  }

  private static void flush(List<String> parts, StringBuilder current) {
    add(parts, current.toString());
    current.setLength(0);
  }

  private static void add(List<String> parts, String value) {
    String trimmed = stripBlankLines(value);
    if (!trimmed.isEmpty()) parts.add(trimmed);
  }

  /** Removes leading/trailing blank lines but keeps the indentation of the first code line. */
  static String stripBlankLines(String value) {
    int start = 0;
    int end = value.length();
    int from = 0;
    while (from < end) {
      int nl = value.indexOf('\n', from);
      int lineEnd = nl < 0 ? end : nl;
      if (!value.substring(from, lineEnd).isBlank()) break;
      from = nl < 0 ? end : nl + 1;
      start = from;
    }
    return value.substring(start, end).stripTrailing();
  }
}
