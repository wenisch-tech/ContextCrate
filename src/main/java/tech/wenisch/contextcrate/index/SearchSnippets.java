package tech.wenisch.contextcrate.index;

import java.util.Locale;

/** A short excerpt of a text around the first occurrence of the query, for backends without server-side highlighting. */
final class SearchSnippets {
  private SearchSnippets() {}

  static String snippet(String text, String query) {
    if (text == null) return "";
    if (text.length() <= 240) return text;
    int match = text.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
    int start = match < 0 ? 0 : Math.max(0, match - 100), end = Math.min(text.length(), start + 240);
    return (start > 0 ? "..." : "") + text.substring(start, end) + (end < text.length() ? "..." : "");
  }
}
