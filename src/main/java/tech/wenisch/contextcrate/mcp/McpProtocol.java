package tech.wenisch.contextcrate.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shape of a tool result.
 *
 * <p>Transport and JSON-RPC framing belong to the Model Context Protocol SDK; what remains here is
 * the neutral result map that {@link McpTools} produces and {@link McpToolAdapter} converts into a
 * protocol object. Keeping it map-shaped lets the tools be tested without the SDK in the way.
 *
 * <p>Business failures travel as {@code isError=true} inside a result rather than as JSON-RPC
 * errors, so the model is told what went wrong and can correct itself.
 */
public final class McpProtocol {
  public static final String CONTENT = "content";
  public static final String STRUCTURED_CONTENT = "structuredContent";
  public static final String IS_ERROR = "isError";
  public static final String TEXT = "text";

  private McpProtocol() {}

  public static Map<String, Object> toolResult(String text, Object structured, boolean failed) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put(CONTENT, List.of(Map.of("type", TEXT, TEXT, text)));
    if (structured != null) payload.put(STRUCTURED_CONTENT, structured);
    payload.put(IS_ERROR, failed);
    return payload;
  }

  public static Map<String, Object> toolResult(String text, Object structured) {
    return toolResult(text, structured, false);
  }

  public static Map<String, Object> toolFailure(String text) {
    return toolResult(text, null, true);
  }
}
