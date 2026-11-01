package tech.wenisch.contextcrate.service.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;

/**
 * Reads Markdown with a real CommonMark parser. Fenced and indented code come out as code blocks
 * with their language and exact text; prose is flattened to plain text, but inline code, such as
 * {@code snake_case} or {@code List<String>}, is kept verbatim.
 */
public final class MarkdownBlocks {
  private static final Parser PARSER =
      Parser.builder().extensions(Set.of(TablesExtension.create())).build();

  public record Result(List<Block> blocks, List<String> headings) {}

  private MarkdownBlocks() {}

  public static Result parse(String markdown) {
    Node document = PARSER.parse(markdown);
    List<Block> blocks = new ArrayList<>();
    List<String> headings = new ArrayList<>();
    String[] stack = new String[7];
    int depth = 0;
    for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
      if (node instanceof Heading heading) {
        String text = inline(heading).strip();
        if (text.isEmpty()) continue;
        headings.add(text);
        int level = Math.min(6, Math.max(1, heading.getLevel()));
        stack[level] = text;
        for (int i = level + 1; i <= 6; i++) stack[i] = null;
        depth = level;
        continue;
      }
      List<String> path = new ArrayList<>();
      for (int i = 1; i <= Math.min(depth, 6); i++) if (stack[i] != null) path.add(stack[i]);
      block(node, path, blocks);
    }
    return new Result(blocks, headings);
  }

  private static void block(Node node, List<String> path, List<Block> out) {
    if (node instanceof FencedCodeBlock fence) {
      String info = fence.getInfo() == null ? "" : fence.getInfo().strip();
      String language = info.isEmpty() ? null : info.split("[\\s{,]")[0].toLowerCase();
      add(out, Block.code(path, language, stripNewlines(fence.getLiteral())));
    } else if (node instanceof IndentedCodeBlock indented) {
      add(out, Block.code(path, null, stripNewlines(indented.getLiteral())));
    } else if (node instanceof BlockQuote quote) {
      for (Node child = quote.getFirstChild(); child != null; child = child.getNext())
        block(child, path, out);
    } else if (node instanceof ThematicBreak) {
      // no content
    } else if (node instanceof HtmlBlock html) {
      String text = html.getLiteral().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").strip();
      if (!text.isEmpty()) add(out, Block.prose(path, text));
    } else if (node instanceof TableBlock table) {
      add(out, Block.prose(path, table(table)));
    } else if (node instanceof ListBlock list) {
      add(out, Block.prose(path, list(list, 0)));
    } else {
      add(out, Block.prose(path, inline(node).strip()));
    }
  }

  private static void add(List<Block> out, Block block) {
    if (!block.text().isBlank()) out.add(block);
  }

  private static String list(ListBlock list, int indent) {
    StringBuilder out = new StringBuilder();
    int number = list instanceof OrderedList ordered && ordered.getMarkerStartNumber() != null
        ? ordered.getMarkerStartNumber() : 1;
    for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
      String marker = list instanceof OrderedList ? number++ + ". " : "- ";
      boolean first = true;
      for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
        String text;
        if (child instanceof ListBlock nested) text = list(nested, indent + 1);
        else if (child instanceof FencedCodeBlock fence)
          text = "```" + (fence.getInfo() == null ? "" : fence.getInfo().strip()) + "\n"
              + stripNewlines(fence.getLiteral()) + "\n```";
        else if (child instanceof IndentedCodeBlock code) text = stripNewlines(code.getLiteral());
        else text = inline(child).strip();
        if (text.isEmpty()) continue;
        String pad = "  ".repeat(indent);
        if (child instanceof ListBlock) out.append(text).append('\n');
        else {
          out.append(pad).append(first ? marker : "  ").append(text.replace("\n", "\n" + pad + "  "))
              .append('\n');
          first = false;
        }
      }
    }
    return out.toString().stripTrailing();
  }

  private static String table(TableBlock table) {
    StringBuilder out = new StringBuilder();
    for (Node section = table.getFirstChild(); section != null; section = section.getNext())
      for (Node row = section.getFirstChild(); row instanceof TableRow; row = row.getNext()) {
        List<String> cells = new ArrayList<>();
        for (Node cell = row.getFirstChild(); cell instanceof TableCell; cell = cell.getNext())
          cells.add(inline(cell).strip().replace('\n', ' '));
        out.append(String.join(" | ", cells)).append('\n');
      }
    return out.toString().stripTrailing();
  }

  /** Plain text of an inline subtree: markup removed, inline code and line breaks preserved. */
  private static String inline(Node node) {
    StringBuilder out = new StringBuilder();
    appendInline(node, out);
    return out.toString();
  }

  private static void appendInline(Node node, StringBuilder out) {
    for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
      if (child instanceof Text text) out.append(text.getLiteral());
      else if (child instanceof Code code) out.append('`').append(code.getLiteral()).append('`');
      else if (child instanceof SoftLineBreak || child instanceof HardLineBreak) out.append('\n');
      else if (child instanceof HtmlInline) {
        // dropped, like the old tag stripping
      } else if (child instanceof Image image) {
        appendInline(image, out);
      } else appendInline(child, out); // emphasis, strong, link, paragraph, ...
    }
  }

  private static String stripNewlines(String literal) {
    if (literal == null) return "";
    String value = literal.replace("\r\n", "\n");
    while (value.endsWith("\n")) value = value.substring(0, value.length() - 1);
    while (value.startsWith("\n")) value = value.substring(1);
    return value.stripTrailing();
  }
}
