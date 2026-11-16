package tech.wenisch.contextcrate.service.chunking;

/**
 * Finds tables inside chunk text. Both parsers write a table as one line per row with the cells joined by
 * {@code " | "}, so a table is two or more consecutive lines that contain that separator.
 */
public final class TableDetector {
  private TableDetector() {}

  public static boolean containsTable(String text) {
    if (text == null || text.isBlank()) return false;
    int run = 0;
    for (String line : text.split("\\R")) {
      if (line.contains(" | ")) { if (++run >= 2) return true; } else run = 0;
    }
    return false;
  }
}
