package tech.wenisch.contextcrate.service.chunking;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;

/**
 * Splits a source file into chunks that follow its structure: one chunk per function, class or
 * method (large classes are split into their members), small neighbours packed together, and the
 * comment above a declaration kept with it. Files without a grammar, files tree-sitter cannot
 * parse, and a missing native library all fall back to splitting on blank lines and lines.
 */
public final class CodeChunker {
  private static final Logger log = LoggerFactory.getLogger(CodeChunker.class);
  private static final long PARSE_TIMEOUT_MICROS = 3_000_000L;
  private static final int MAX_DEPTH = 6;
  private static volatile boolean nativeBroken;

  private CodeChunker() {}

  /** A piece of the file, as whole lines [startRow, endRow] (0-based, inclusive). */
  private record Unit(int startRow, int endRow, String symbol) {}

  public static List<ChunkDraft> chunk(String path, String source, ChunkOptions options) {
    String language = LanguageRegistry.languageOf(path);
    String[] lines = source.split("\n", -1);
    List<Unit> units = null;
    if (LanguageRegistry.hasGrammar(language) && !nativeBroken) {
      try {
        units = structural(language, source, lines, options);
      } catch (Throwable error) { // UnsatisfiedLinkError, TSException, anything native
        if (error instanceof LinkageError) {
          nativeBroken = true;
          log.warn("tree-sitter is unavailable, code is split on blank lines instead: {}",
              error.toString());
        } else {
          log.debug("tree-sitter could not parse {}: {}", path, error.toString());
        }
      }
    }
    if (units == null || units.isEmpty()) return lineChunks(path, language, lines, options);
    return pack(path, language, lines, units, options);
  }

  // ---- structural splitting -------------------------------------------------------------

  private static List<Unit> structural(String language, String source, String[] lines,
      ChunkOptions options) {
    TSParser parser = new TSParser();
    parser.setLanguage(LanguageRegistry.grammar(language));
    parser.setTimeoutMicros(PARSE_TIMEOUT_MICROS);
    TSTree tree = parser.parseString(null, source);
    if (tree == null) return null;
    TSNode root = tree.getRootNode();
    if (root == null || root.isNull() || root.getChildCount() == 0) return null;
    List<Unit> units = new ArrayList<>();
    children(root, lines, "", options, 0, units, lines.length - 1);
    return units;
  }

  /** Adds one unit per child of {@code parent}; oversize children are split into their members. */
  private static void children(TSNode parent, String[] lines, String prefix, ChunkOptions options,
      int depth, List<Unit> out, int lastRow) {
    int count = parent.getNamedChildCount();
    for (int i = 0; i < count; i++) {
      TSNode child = parent.getNamedChild(i);
      if (child == null || child.isNull()) continue;
      int start = child.getStartPoint().getRow();
      int end = Math.min(lastRow, endRow(child));
      if (child.getType().contains("comment")) {
        out.add(new Unit(start, end, null)); // standalone unless merged below
        continue;
      }
      // A comment directly above belongs to this declaration.
      if (!out.isEmpty()) {
        Unit previous = out.get(out.size() - 1);
        if (previous.symbol() == null && previous.endRow() + 1 >= start) {
          int first = out.size() - 1;
          while (first > 0 && out.get(first - 1).symbol() == null
              && out.get(first - 1).endRow() + 1 >= out.get(first).startRow()) first--;
          start = out.get(first).startRow();
          while (out.size() > first) out.remove(out.size() - 1);
        }
      }
      String name = symbol(prefix, child, lines);
      if (size(lines, start, end) <= options.size() || depth >= MAX_DEPTH) {
        out.add(new Unit(start, end, name));
        continue;
      }
      split(child, lines, name, options, depth + 1, out, start, end);
    }
  }

  /** Splits a large declaration: its header, then each member, then whatever closes it. */
  private static void split(TSNode node, String[] lines, String name, ChunkOptions options,
      int depth, List<Unit> out, int start, int end) {
    TSNode body = node.getChildByFieldName("body");
    TSNode container = body != null && !body.isNull() ? body : node;
    if (container.getNamedChildCount() == 0) {
      out.add(new Unit(start, end, name));
      return;
    }
    List<Unit> members = new ArrayList<>();
    children(container, lines, name, options, depth, members, end);
    if (members.isEmpty()) {
      out.add(new Unit(start, end, name));
      return;
    }
    int firstMember = members.get(0).startRow();
    if (firstMember > start) out.add(new Unit(start, firstMember - 1, name));
    out.addAll(members);
    int lastMember = members.get(members.size() - 1).endRow();
    if (lastMember < end) out.add(new Unit(lastMember + 1, end, name));
  }

  private static int endRow(TSNode node) {
    var end = node.getEndPoint();
    // A node ending at column 0 of a line (trailing newline) does not include that line.
    return end.getColumn() == 0 && end.getRow() > node.getStartPoint().getRow()
        ? end.getRow() - 1 : end.getRow();
  }

  // ---- symbol names ---------------------------------------------------------------------

  private static String symbol(String prefix, TSNode node, String[] lines) {
    String name = nameOf(node, lines, 0);
    if (name == null || name.isBlank()) return prefix.isEmpty() ? node.getType() : prefix;
    return prefix.isEmpty() ? name : prefix + "." + name;
  }

  private static String nameOf(TSNode node, String[] lines, int depth) {
    if (node == null || node.isNull() || depth > 4) return null;
    for (String field : new String[] {"name", "type", "declarator", "declaration", "left"}) {
      TSNode child = node.getChildByFieldName(field);
      if (child == null || child.isNull()) continue;
      String type = child.getType();
      if (type.contains("identifier") || type.endsWith("_name") || type.equals("name")
          || child.getNamedChildCount() == 0) {
        String text = leafText(child, lines);
        if (text != null) return text;
      }
      String nested = nameOf(child, lines, depth + 1);
      if (nested != null) return nested;
    }
    return null;
  }

  /** Text of a single-line node, read from the source lines (columns are UTF-8 bytes). */
  private static String leafText(TSNode node, String[] lines) {
    int row = node.getStartPoint().getRow();
    if (row >= lines.length || node.getEndPoint().getRow() != row) return null;
    String line = lines[row];
    byte[] bytes = line.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    int from = Math.min(bytes.length, node.getStartPoint().getColumn());
    int to = Math.min(bytes.length, node.getEndPoint().getColumn());
    if (to <= from) return null;
    String text = new String(bytes, from, to - from, java.nio.charset.StandardCharsets.UTF_8);
    return text.length() > 120 ? text.substring(0, 120) : text;
  }

  // ---- packing --------------------------------------------------------------------------

  private static int size(String[] lines, int start, int end) {
    int total = 0;
    for (int i = start; i <= end && i < lines.length; i++) total += lines[i].length() + 1;
    return total;
  }

  private static String text(String[] lines, int start, int end) {
    StringBuilder out = new StringBuilder();
    for (int i = start; i <= end && i < lines.length; i++) out.append(lines[i]).append('\n');
    return TextSplitter.stripBlankLines(out.toString());
  }

  private static List<ChunkDraft> pack(String path, String language, String[] lines,
      List<Unit> units, ChunkOptions options) {
    List<ChunkDraft> result = new ArrayList<>();
    int groupStart = -1, groupEnd = -1;
    String groupSymbol = null;
    int groupMembers = 0;
    for (Unit unit : units) {
      if (groupStart >= 0 && size(lines, groupStart, unit.endRow()) > options.size()) {
        emit(path, language, lines, groupStart, groupEnd, groupSymbol, groupMembers, options, result);
        groupStart = -1;
      }
      if (groupStart < 0) {
        groupStart = unit.startRow();
        groupSymbol = unit.symbol();
        groupMembers = 0;
      }
      groupEnd = unit.endRow();
      groupMembers++;
    }
    if (groupStart >= 0)
      emit(path, language, lines, groupStart, groupEnd, groupSymbol, groupMembers, options, result);
    return result;
  }

  private static void emit(String path, String language, String[] lines, int start, int end,
      String symbol, int members, ChunkOptions options, List<ChunkDraft> out) {
    String body = text(lines, start, end);
    if (body.isBlank()) return;
    String label = symbol == null ? fileName(path) : symbol;
    if (members > 1 && symbol != null) label = symbol + " …";
    if (body.length() <= options.size()) {
      out.add(new ChunkDraft(label, body, ChunkDraft.CODE, language, label, start + 1, end + 1));
      return;
    }
    int row = start;
    for (String part : TextSplitter.lines(body, options.size())) {
      int partLines = part.split("\n", -1).length;
      out.add(new ChunkDraft(label, part, ChunkDraft.CODE, language, label, row + 1,
          row + partLines));
      row += partLines;
    }
  }

  // ---- fallback -------------------------------------------------------------------------

  /** Splits on blank lines first (paragraph-like groups), then on lines; never mid-line. */
  private static List<ChunkDraft> lineChunks(String path, String language, String[] lines,
      ChunkOptions options) {
    String source = String.join("\n", lines);
    List<ChunkDraft> result = new ArrayList<>();
    int row = 0;
    for (String part : TextSplitter.lines(source, options.size())) {
      int count = part.split("\n", -1).length;
      result.add(new ChunkDraft(fileName(path), part, ChunkDraft.CODE, language, null, row + 1,
          row + count));
      row += count;
    }
    return result;
  }

  private static String fileName(String path) {
    return path == null ? "code" : path.substring(path.lastIndexOf('/') + 1);
  }
}
