package tech.wenisch.contextcrate.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Constants and message shapes of the Model Context Protocol's JSON-RPC layer.
 *
 * <p>The server is stateless: it answers every POST with a single JSON object, offers no SSE stream,
 * and issues no session id. The specification allows all three, so no session bookkeeping, no
 * resumability and no server-initiated notifications are implemented.
 */
public final class McpProtocol {
  /** Newest supported revision; also the fallback when a client asks for something unknown. */
  public static final String LATEST_VERSION = "2025-11-25";

  /** Assumed revision when a client omits the {@code MCP-Protocol-Version} header. */
  public static final String DEFAULT_VERSION = "2025-03-26";

  public static final List<String> SUPPORTED_VERSIONS =
      List.of("2025-11-25", "2025-06-18", "2025-03-26");

  public static final int PARSE_ERROR = -32700;
  public static final int INVALID_REQUEST = -32600;
  public static final int METHOD_NOT_FOUND = -32601;
  public static final int INVALID_PARAMS = -32602;
  public static final int INTERNAL_ERROR = -32603;

  private McpProtocol() {}

  /** Returns the version to answer with: the client's, when we speak it, otherwise our newest. */
  public static String negotiate(String requested) {
    return requested != null && SUPPORTED_VERSIONS.contains(requested) ? requested : LATEST_VERSION;
  }

  public static boolean supported(String version) {
    return SUPPORTED_VERSIONS.contains(version);
  }

  /** A JSON-RPC result envelope. {@code id} is echoed verbatim, including when it is null. */
  public static Map<String, Object> result(Object id, Object payload) {
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("jsonrpc", "2.0");
    message.put("id", id);
    message.put("result", payload);
    return message;
  }

  /** A JSON-RPC error envelope. Reserved for protocol faults, never for tool failures. */
  public static Map<String, Object> error(Object id, int code, String message) {
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("code", code);
    error.put("message", message);
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("jsonrpc", "2.0");
    envelope.put("id", id);
    envelope.put("error", error);
    return envelope;
  }

  /**
   * A tool result. Business failures travel here with {@code isError=true} rather than as JSON-RPC
   * errors, so the model sees the explanation and can correct itself.
   */
  public static Map<String, Object> toolResult(String text, Object structured, boolean failed) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("content", List.of(Map.of("type", "text", "text", text)));
    if (structured != null) payload.put("structuredContent", structured);
    payload.put("isError", failed);
    return payload;
  }

  public static Map<String, Object> toolResult(String text, Object structured) {
    return toolResult(text, structured, false);
  }

  public static Map<String, Object> toolFailure(String text) {
    return toolResult(text, null, true);
  }
}
