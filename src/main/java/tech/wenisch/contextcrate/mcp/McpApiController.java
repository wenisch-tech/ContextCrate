package tech.wenisch.contextcrate.mcp;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.contextcrate.domain.Crate;
import tools.jackson.databind.JsonNode;

/**
 * Model Context Protocol endpoint, so external AI clients can retrieve from a crate.
 *
 * <p>Two shapes of the same handler: a crate-bound path for service credentials, and a global path
 * where the crate is chosen per call. Both live under {@code /api/v1/**}, which is already exempt
 * from CSRF — mandatory for a POST-only protocol.
 *
 * <p>Stateless by design: every POST is answered with one JSON object, GET and DELETE return 405,
 * and no session id is issued. The specification permits all three.
 */
@RestController
public class McpApiController {
  private static final String SERVER_NAME = "contextcrate";

  private final McpCrateResolver crates;
  private final McpToolCatalog catalog;
  private final McpTools tools;
  private final Set<String> allowedOrigins;
  private final String version;

  public McpApiController(McpCrateResolver crates, McpToolCatalog catalog, McpTools tools,
      @Value("${contextcrate.mcp.allowed-origins:}") String allowedOrigins,
      @Value("${contextcrate.version:dev}") String version) {
    this.crates = crates;
    this.catalog = catalog;
    this.tools = tools;
    this.version = version;
    this.allowedOrigins = Set.of(allowedOrigins.split("\\s*,\\s*")).stream()
        .filter(origin -> !origin.isBlank())
        .map(origin -> origin.toLowerCase(Locale.ROOT))
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  @PostMapping(value = "/api/v1/crates/{crateId}/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Object> crateScoped(@PathVariable UUID crateId, @RequestBody JsonNode message,
      @RequestHeader(value = "Origin", required = false) String origin,
      @RequestHeader(value = "MCP-Protocol-Version", required = false) String protocolVersion) {
    return handle(crateId, message, origin, protocolVersion);
  }

  @PostMapping(value = "/api/v1/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Object> global(@RequestBody JsonNode message,
      @RequestHeader(value = "Origin", required = false) String origin,
      @RequestHeader(value = "MCP-Protocol-Version", required = false) String protocolVersion) {
    return handle(null, message, origin, protocolVersion);
  }

  /** The specification allows a server with no server-initiated stream to refuse GET and DELETE. */
  @RequestMapping(value = {"/api/v1/mcp", "/api/v1/crates/{crateId}/mcp"},
      method = {RequestMethod.GET, RequestMethod.DELETE})
  public ResponseEntity<Object> unsupported() {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .body(McpProtocol.error(null, McpProtocol.INVALID_REQUEST,
            "This MCP endpoint is stateless: it accepts POST only."));
  }

  private ResponseEntity<Object> handle(UUID crateId, JsonNode message, String origin, String protocolVersion) {
    if (origin != null && !origin.isBlank()
        && !allowedOrigins.contains(origin.trim().toLowerCase(Locale.ROOT)))
      return ResponseEntity.status(HttpStatus.FORBIDDEN)
          .body(McpProtocol.error(null, McpProtocol.INVALID_REQUEST, "Origin is not allowed."));
    if (protocolVersion != null && !protocolVersion.isBlank() && !McpProtocol.supported(protocolVersion))
      return ResponseEntity.badRequest()
          .body(McpProtocol.error(null, McpProtocol.INVALID_REQUEST,
              "Unsupported MCP-Protocol-Version " + protocolVersion));
    if (message == null || !message.isObject())
      return ResponseEntity.ok(
          McpProtocol.error(null, McpProtocol.PARSE_ERROR, "Expected a JSON-RPC request object."));

    Object id = id(message);
    String method = message.hasNonNull("method") ? message.get("method").asString() : null;
    if (method == null)
      return ResponseEntity.ok(
          McpProtocol.error(id, McpProtocol.INVALID_REQUEST, "Missing \"method\"."));

    // Notifications carry no id and expect an empty 202.
    if (method.startsWith("notifications/"))
      return ResponseEntity.accepted().build();

    JsonNode params = message.get("params");
    try {
      return switch (method) {
        case "initialize" -> ResponseEntity.ok(McpProtocol.result(id, initialize(crateId, params)));
        case "ping" -> ResponseEntity.ok(McpProtocol.result(id, Map.of()));
        case "tools/list" -> ResponseEntity.ok(
            McpProtocol.result(id, Map.of("tools", catalog.tools(bound(crateId)))));
        case "tools/call" -> ResponseEntity.ok(McpProtocol.result(id, call(crateId, params)));
        default -> ResponseEntity.ok(
            McpProtocol.error(id, McpProtocol.METHOD_NOT_FOUND, "Unknown method " + method));
      };
    } catch (AccessDeniedException denied) {
      // Let Spring Security answer with 403; an authorization failure is not a protocol fault.
      throw denied;
    } catch (UnknownToolException unknown) {
      return ResponseEntity.ok(
          McpProtocol.error(id, McpProtocol.METHOD_NOT_FOUND, unknown.getMessage()));
    } catch (IllegalArgumentException | IllegalStateException invalid) {
      return ResponseEntity.ok(
          McpProtocol.error(id, McpProtocol.INVALID_PARAMS,
              invalid.getMessage() == null ? "Invalid request" : invalid.getMessage()));
    } catch (Exception failure) {
      return ResponseEntity.ok(
          McpProtocol.error(id, McpProtocol.INTERNAL_ERROR,
              failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
    }
  }

  private Map<String, Object> initialize(UUID crateId, JsonNode params) {
    String requested = params != null && params.hasNonNull("protocolVersion")
        ? params.get("protocolVersion").asString()
        : null;
    Crate crate = bound(crateId);
    Map<String, Object> serverInfo = new LinkedHashMap<>();
    serverInfo.put("name", SERVER_NAME);
    serverInfo.put("title", crate == null ? "ContextCrate" : "ContextCrate — " + crate.getName());
    serverInfo.put("version", version);

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("protocolVersion", McpProtocol.negotiate(requested));
    payload.put("capabilities", Map.of("tools", Map.of()));
    payload.put("serverInfo", serverInfo);
    payload.put("instructions", catalog.instructions(crate));
    return payload;
  }

  private Map<String, Object> call(UUID crateId, JsonNode params) throws Exception {
    String name = params != null && params.hasNonNull("name") ? params.get("name").asString() : null;
    if (name == null) throw new IllegalArgumentException("tools/call requires a tool name");
    JsonNode arguments = params.get("arguments");

    if (name.equals("list_crates")) return tools.listCrates(crates.available());

    Crate crate;
    try {
      String requested = arguments != null && arguments.hasNonNull("crate")
          ? arguments.get("crate").asString()
          : null;
      crate = crates.resolve(crateId, requested);
    } catch (McpCrateResolver.UnresolvedCrateException unresolved) {
      return McpProtocol.toolFailure(unresolved.getMessage());
    }

    return switch (name) {
      case "search_crate" -> tools.search(crate, arguments);
      case "ask_crate" -> tools.ask(crate, arguments);
      case "fetch_document" -> tools.fetchDocument(crate, arguments);
      case "list_documents" -> tools.listDocuments(crate, arguments);
      case "list_sources" -> tools.listSources(crate);
      default -> throw new UnknownToolException(name);
    };
  }

  /** Resolves a path-bound crate for listing and introspection, or null on the global endpoint. */
  private Crate bound(UUID crateId) {
    return crateId == null ? null : crates.resolve(crateId, null);
  }

  static class UnknownToolException extends RuntimeException {
    UnknownToolException(String name) {
      super("Unknown tool " + name);
    }
  }

  private static Object id(JsonNode message) {
    JsonNode id = message.get("id");
    if (id == null || id.isNull()) return null;
    return id.isNumber() ? id.numberValue() : id.asString();
  }

  /**
   * A body Jackson cannot read never reaches {@link #handle}, so it is mapped here rather than by
   * the shared REST advice, which would answer with a plain HTTP envelope instead of JSON-RPC.
   */
  @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
  ResponseEntity<Object> unreadable() {
    return ResponseEntity.ok(
        McpProtocol.error(null, McpProtocol.PARSE_ERROR, "Request body is not valid JSON."));
  }
}
