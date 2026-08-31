package tech.wenisch.contextcrate.mcp;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tech.wenisch.contextcrate.answer.AnswerService;

/**
 * Renders retrieved sources the way the dashboard does, in both forms an MCP result can carry.
 *
 * <p>The numbered text block is the load-bearing half: many clients hand only {@code content} to the
 * model, so that is the only place the {@code [n]} markers in an answer become resolvable. The same
 * fields also go out as {@code structuredContent} for clients that render citations themselves.
 */
public final class McpCitations {
  /** Matches the {@code git+<url>@<sha>/<path>} source URIs produced by the Git connector. */
  private static final Pattern GIT_URI = Pattern.compile("^git\\+(.+)@[0-9a-f]{40}/.+$", Pattern.CASE_INSENSITIVE);

  private McpCitations() {}

  /** The numbered citation list, or an empty string when there is nothing to cite. */
  public static String text(List<AnswerService.Source> sources) {
    if (sources.isEmpty()) return "";
    StringBuilder rendered = new StringBuilder("\n\nRetrieved sources:\n");
    for (AnswerService.Source source : sources) {
      rendered
          .append('[')
          .append(source.citation())
          .append("] ")
          .append(source.title() == null || source.title().isBlank() ? source.sourceUri() : source.title())
          .append(" — ")
          .append(displayUri(source.sourceUri()))
          .append('\n');
      if (source.snippet() != null && !source.snippet().isBlank())
        rendered.append("    ").append(source.snippet().strip()).append('\n');
    }
    return rendered.toString();
  }

  /** The same sources as structured data. Citation numbers are preserved, never re-assigned. */
  public static List<Map<String, Object>> structured(List<AnswerService.Source> sources) {
    return sources.stream().map(McpCitations::entry).toList();
  }

  private static Map<String, Object> entry(AnswerService.Source source) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("citation", source.citation());
    value.put("title", source.title());
    value.put("sourceUri", source.sourceUri());
    value.put("snippet", source.snippet());
    value.put("documentId", string(source.documentId()));
    value.put("chunkId", string(source.chunkId()));
    value.put("chunkOrdinal", source.chunkOrdinal());
    value.put("score", source.score());
    return value;
  }

  /**
   * Unwraps a Git source URI to the underlying repository URL for display, mirroring the dashboard.
   * Anything that is not an {@code http(s)} URL is shown as-is; the raw value always stays in the
   * structured payload because it is the stable identifier.
   */
  public static String displayUri(String sourceUri) {
    if (sourceUri == null) return "";
    Matcher git = GIT_URI.matcher(sourceUri);
    String candidate = git.matches() ? git.group(1) : sourceUri;
    return safe(candidate) ? candidate : sourceUri;
  }

  private static boolean safe(String value) {
    try {
      String scheme = URI.create(value).getScheme();
      if (scheme == null) return false;
      scheme = scheme.toLowerCase(Locale.ROOT);
      return scheme.equals("http") || scheme.equals("https");
    } catch (IllegalArgumentException notAUri) {
      return false;
    }
  }

  private static String string(java.util.UUID value) {
    return value == null ? null : value.toString();
  }
}
