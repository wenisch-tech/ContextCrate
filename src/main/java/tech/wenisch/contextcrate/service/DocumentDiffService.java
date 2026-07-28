package tech.wenisch.contextcrate.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import tech.wenisch.contextcrate.domain.NormalizedDocument;

/** Produces a compact, line-oriented unified diff without exposing document markup as HTML. */
@Service
public class DocumentDiffService {
  private static final long MAX_MATRIX_CELLS = 1_000_000;

  public String unified(NormalizedDocument previous, NormalizedDocument current) {
    String[] oldLines = lines(previous.getBody());
    String[] newLines = lines(current.getBody());
    StringBuilder output = new StringBuilder("--- v").append(previous.getVersionNumber())
        .append('\n').append("+++ v").append(current.getVersionNumber()).append('\n');
    if ((long) oldLines.length * newLines.length > MAX_MATRIX_CELLS) {
      appendAll(output, '-', oldLines);
      appendAll(output, '+', newLines);
      return output.toString();
    }
    int[][] lcs = new int[oldLines.length + 1][newLines.length + 1];
    for (int old = oldLines.length - 1; old >= 0; old--)
      for (int newer = newLines.length - 1; newer >= 0; newer--)
        lcs[old][newer] = oldLines[old].equals(newLines[newer]) ? lcs[old + 1][newer + 1] + 1
            : Math.max(lcs[old + 1][newer], lcs[old][newer + 1]);
    int old = 0, newer = 0;
    while (old < oldLines.length || newer < newLines.length) {
      if (old < oldLines.length && newer < newLines.length && oldLines[old].equals(newLines[newer])) {
        output.append(' ').append(oldLines[old++]).append('\n'); newer++;
      } else if (newer < newLines.length && (old == oldLines.length || lcs[old][newer + 1] >= lcs[old + 1][newer])) {
        output.append('+').append(newLines[newer++]).append('\n');
      } else output.append('-').append(oldLines[old++]).append('\n');
    }
    return output.toString();
  }

  private static String[] lines(String value) { return value.split("\\R", -1); }
  private static void appendAll(StringBuilder output, char prefix, String[] lines) {
    for (String line : lines) output.append(prefix).append(line).append('\n');
  }
}
