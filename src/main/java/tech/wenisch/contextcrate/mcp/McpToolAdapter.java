package tech.wenisch.contextcrate.mcp;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.domain.Crate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Binds ContextCrate's tools to the Model Context Protocol SDK.
 *
 * <p>Two conversions and one safety net:
 *
 * <ul>
 *   <li>arguments arrive as a {@code Map} and are handed to {@link McpTools} as a Jackson node, so
 *       the tools themselves need no change;
 *   <li>the tools' result map becomes a {@link McpSchema.CallToolResult};
 *   <li>the caller's {@code Authentication} is re-established around every invocation.
 * </ul>
 *
 * <p>That last point matters more than it looks. Authorization runs through {@code
 * SecurityContextHolder}, which is a thread-local; the SDK is free to run a tool on a thread other
 * than the one that served the HTTP request, and there the context would be empty and every call
 * would fail. {@link McpTransportContextFactory} captures the authentication while the request
 * thread still has it, and this class puts it back before calling the tool — which makes the whole
 * arrangement independent of the SDK's threading.
 */
@Component
public class McpToolAdapter {
  private final McpTools tools;
  private final McpCrateResolver crates;
  private final McpToolCatalog catalog;
  private final JsonMapper mapper = JsonMapper.builder().build();

  public McpToolAdapter(McpTools tools, McpCrateResolver crates, McpToolCatalog catalog) {
    this.tools = tools;
    this.crates = crates;
    this.catalog = catalog;
  }

  /** Every tool, ready to be registered on a server. */
  public List<McpServerFeatures.SyncToolSpecification> specifications() {
    List<McpServerFeatures.SyncToolSpecification> specifications = new ArrayList<>();
    for (Map<String, Object> definition : catalog.tools()) {
      String name = String.valueOf(definition.get("name"));
      @SuppressWarnings("unchecked")
      Map<String, Object> schema = (Map<String, Object>) definition.get("inputSchema");
      McpSchema.Tool tool = McpSchema.Tool.builder()
          .name(name)
          .description(String.valueOf(definition.get("description")))
          .inputSchema(schema)
          .build();
      specifications.add(new McpServerFeatures.SyncToolSpecification(tool,
          (exchange, request) -> invoke(name, exchange, request)));
    }
    return specifications;
  }

  private McpSchema.CallToolResult invoke(
      String name, McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
    var context = exchange.transportContext();
    Authentication authentication =
        (Authentication) context.get(McpTransportContextFactory.AUTHENTICATION);
    UUID pathCrateId = (UUID) context.get(McpTransportContextFactory.CRATE_ID);

    var previous = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null) SecurityContextHolder.getContext().setAuthentication(authentication);
    try {
      return result(run(name, pathCrateId, request));
    } catch (AccessDeniedException denied) {
      return result(McpProtocol.toolFailure(
          denied.getMessage() == null ? "Access denied." : denied.getMessage()));
    } catch (McpCrateResolver.UnresolvedCrateException unresolved) {
      return result(McpProtocol.toolFailure(unresolved.getMessage()));
    } catch (Exception failure) {
      return result(McpProtocol.toolFailure(
          failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
    } finally {
      SecurityContextHolder.getContext().setAuthentication(previous);
    }
  }

  private Map<String, Object> run(
      String name, UUID pathCrateId, McpSchema.CallToolRequest request) throws Exception {
    JsonNode arguments = mapper.valueToTree(
        request.arguments() == null ? Map.of() : request.arguments());

    if (name.equals("list_crates")) return tools.listCrates(crates.available());

    String requested = arguments.hasNonNull("crate") ? arguments.get("crate").asString() : null;
    Crate crate = crates.resolve(pathCrateId, requested);

    return switch (name) {
      case "search_crate" -> tools.search(crate, arguments);
      case "ask_crate" -> tools.ask(crate, arguments);
      case "fetch_document" -> tools.fetchDocument(crate, arguments);
      case "list_documents" -> tools.listDocuments(crate, arguments);
      case "list_sources" -> tools.listSources(crate);
      default -> McpProtocol.toolFailure("Unknown tool " + name);
    };
  }

  /** Converts the tools' neutral result map into the protocol's own result object. */
  static McpSchema.CallToolResult result(Map<String, Object> payload) {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> content =
        (List<Map<String, Object>>) payload.getOrDefault(McpProtocol.CONTENT, List.of());
    var builder = McpSchema.CallToolResult.builder()
        .isError(Boolean.TRUE.equals(payload.get(McpProtocol.IS_ERROR)));
    for (Map<String, Object> block : content)
      builder.addTextContent(String.valueOf(block.get(McpProtocol.TEXT)));
    Object structured = payload.get(McpProtocol.STRUCTURED_CONTENT);
    if (structured != null) builder.structuredContent(structured);
    return builder.build();
  }
}
