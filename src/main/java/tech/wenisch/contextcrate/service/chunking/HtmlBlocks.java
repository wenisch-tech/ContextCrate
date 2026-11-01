package tech.wenisch.contextcrate.service.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * Reads a rendered HTML page into blocks. Headings form the heading path, {@code <pre>} becomes
 * a code block that keeps its line breaks and indentation, tables and lists stay in one piece.
 */
public final class HtmlBlocks {
  private static final Set<String> CONTAINERS = Set.of("div", "section", "article", "main",
      "body", "details", "summary", "figure", "figcaption", "form", "fieldset", "header", "li",
      "blockquote", "dd", "dt", "dl", "td", "th", "center");
  private static final Pattern LANGUAGE = Pattern.compile(
      "(?:^|\\s)(?:language|lang|highlight-source|highlight-text|brush:?|prettyprint)[-:\\s]*([A-Za-z0-9_+#.-]+)");

  public record Result(List<Block> blocks, List<String> headings) {}

  private HtmlBlocks() {}

  public static Result parse(Element root) {
    State state = new State();
    walk(root, state);
    state.flushInline();
    return new Result(state.blocks, state.headings);
  }

  private static final class State {
    final List<Block> blocks = new ArrayList<>();
    final List<String> headings = new ArrayList<>();
    final String[] stack = new String[7];
    int depth;
    final StringBuilder inline = new StringBuilder();

    List<String> path() {
      List<String> path = new ArrayList<>();
      for (int i = 1; i <= depth; i++) if (stack[i] != null) path.add(stack[i]);
      return path;
    }

    void flushInline() {
      String text = inline.toString().replaceAll("\\s+", " ").strip();
      inline.setLength(0);
      if (!text.isEmpty()) blocks.add(Block.prose(path(), text));
    }
  }

  private static void walk(Element element, State state) {
    for (Node child : element.childNodes()) {
      if (child instanceof TextNode text) {
        state.inline.append(text.text());
      } else if (child instanceof Element el) {
        String tag = el.normalName();
        if (tag.matches("h[1-6]")) {
          state.flushInline();
          String text = el.text().replaceAll("\\s+", " ").strip();
          if (!text.isEmpty()) {
            int level = tag.charAt(1) - '0';
            state.headings.add(text);
            state.stack[level] = text;
            for (int i = level + 1; i <= 6; i++) state.stack[i] = null;
            state.depth = level;
          }
        } else if (tag.equals("pre")) {
          state.flushInline();
          String code = code(el);
          if (!code.isBlank()) state.blocks.add(Block.code(state.path(), language(el), code));
        } else if (tag.equals("table")) {
          state.flushInline();
          String text = table(el);
          if (!text.isBlank()) state.blocks.add(Block.prose(state.path(), text));
        } else if (tag.equals("ul") || tag.equals("ol")) {
          state.flushInline();
          walkList(el, state);
        } else if (tag.equals("p") || tag.equals("blockquote") && !hasBlocks(el)) {
          state.flushInline();
          if (hasBlocks(el)) walk(el, state);
          else {
            state.inline.append(el.text());
            state.flushInline();
          }
        } else if (CONTAINERS.contains(tag) || hasBlocks(el)) {
          state.flushInline();
          walk(el, state);
          state.flushInline();
        } else if (tag.equals("br")) {
          state.inline.append(' ');
        } else {
          state.inline.append(' ').append(el.text()).append(' ');
        }
      }
    }
  }

  private static void walkList(Element list, State state) {
    StringBuilder text = new StringBuilder();
    renderList(list, 0, text, state);
    String value = text.toString().stripTrailing();
    if (!value.isBlank()) state.blocks.add(Block.prose(state.path(), value));
  }

  private static void renderList(Element list, int indent, StringBuilder out, State state) {
    int number = 1;
    boolean ordered = list.normalName().equals("ol");
    for (Element item : list.children()) {
      if (!item.normalName().equals("li")) continue;
      StringBuilder own = new StringBuilder();
      List<Element> nested = new ArrayList<>();
      for (Node n : item.childNodes()) {
        if (n instanceof TextNode t) own.append(t.text());
        else if (n instanceof Element e) {
          if (e.normalName().equals("ul") || e.normalName().equals("ol")) nested.add(e);
          else if (e.normalName().equals("pre")) own.append("\n").append(code(e)).append("\n");
          else own.append(e.text());
        }
      }
      String line = own.toString().replaceAll("[ \\t]+", " ").strip();
      if (!line.isEmpty())
        out.append("  ".repeat(indent)).append(ordered ? number++ + ". " : "- ")
            .append(line.replace("\n", "\n" + "  ".repeat(indent + 1))).append('\n');
      for (Element sub : nested) renderList(sub, indent + 1, out, state);
    }
  }

  private static boolean hasBlocks(Element el) {
    return !el.select("pre, table, ul, ol, h1, h2, h3, h4, h5, h6, p, div, section").isEmpty();
  }

  private static String code(Element pre) {
    String text = pre.wholeText().replace("\r\n", "\n").replace(' ', ' ');
    while (text.startsWith("\n")) text = text.substring(1);
    return text.stripTrailing();
  }

  private static String table(Element table) {
    StringBuilder out = new StringBuilder();
    for (Element row : table.select("tr")) {
      List<String> cells = new ArrayList<>();
      for (Element cell : row.select("> th, > td"))
        cells.add(cell.text().replaceAll("\\s+", " ").strip());
      if (!cells.isEmpty()) out.append(String.join(" | ", cells)).append('\n');
    }
    return out.toString().stripTrailing();
  }

  /** Language from the class names of the {@code pre}, its {@code code} child or the wrapper. */
  static String language(Element pre) {
    List<Element> candidates = new ArrayList<>();
    candidates.add(pre);
    Element code = pre.selectFirst("code");
    if (code != null) candidates.add(code);
    if (pre.parent() != null) candidates.add(pre.parent());
    for (Element candidate : candidates) {
      String classes = candidate.className().toLowerCase(Locale.ROOT);
      Matcher m = LANGUAGE.matcher(classes);
      if (m.find()) return m.group(1);
      String data = candidate.attr("data-lang");
      if (!data.isBlank()) return data.toLowerCase(Locale.ROOT);
    }
    return null;
  }
}
