package tech.wenisch.contextcrate.service.chunking;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Splits text into sentences and joins a selection of them again without changing a character. */
public final class SentenceSplitter {
  private SentenceSplitter() {}

  /** One sentence and whether it is the first of its paragraph. */
  public record Unit(String text, boolean paragraphStart) {}

  /** Paragraphs are separated by blank lines; sentences by the JDK sentence rules. */
  public static List<Unit> split(String text) {
    List<Unit> units = new ArrayList<>();
    for (String paragraph : text.strip().split("\n{2,}")) {
      if (paragraph.isBlank()) continue;
      BreakIterator it = BreakIterator.getSentenceInstance(Locale.ROOT);
      it.setText(paragraph);
      boolean first = true;
      for (int start = it.first(), end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
        String sentence = paragraph.substring(start, end).strip();
        if (sentence.isEmpty()) continue;
        units.add(new Unit(sentence, first));
        first = false;
      }
    }
    return units;
  }

  /** Sentences of the same paragraph are joined with a space, a new paragraph starts after a blank line. */
  public static String join(List<Unit> units) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < units.size(); i++) {
      if (i > 0) sb.append(units.get(i).paragraphStart() ? "\n\n" : " ");
      sb.append(units.get(i).text());
    }
    return sb.toString();
  }
}
