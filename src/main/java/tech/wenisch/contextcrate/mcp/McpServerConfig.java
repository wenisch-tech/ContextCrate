package tech.wenisch.contextcrate.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes ContextCrate as a Model Context Protocol server over Streamable HTTP.
 *
 * <p>The transport comes from the official SDK rather than being written here. That is the whole
 * point of this class: a hand-written endpoint answered POST with JSON and refused GET and DELETE,
 * which is permitted by the specification but leaves real clients unable to connect — they open the
 * server-to-client stream with GET, expect an {@code Mcp-Session-Id}, and negotiate protocol
 * revisions we did not offer. The provider handles all of that, including SSE and session lifetime.
 *
 * <p>Two endpoints, therefore two providers and two server instances — a provider binds to exactly
 * one server. Both are given the same tools. Both live under {@code /api/v1/**}, so they inherit the
 * CSRF exemption and the authentication rule already configured in {@code SecurityConfig}.
 */
@Configuration
public class McpServerConfig {
  static final String CRATE_SCOPED_ENDPOINT = "/api/v1/crates/{crateId}/mcp";
  static final String GLOBAL_ENDPOINT = "/api/v1/mcp";

  private final McpToolAdapter adapter;
  private final McpToolCatalog catalog;
  private final String version;
  private final Set<String> allowedOrigins;

  public McpServerConfig(McpToolAdapter adapter, McpToolCatalog catalog,
      @Value("${contextcrate.version:dev}") String version,
      @Value("${contextcrate.mcp.allowed-origins:}") String allowedOrigins) {
    this.adapter = adapter;
    this.catalog = catalog;
    this.version = version;
    this.allowedOrigins = Set.of(allowedOrigins.split("\\s*,\\s*")).stream()
        .filter(origin -> !origin.isBlank())
        .map(origin -> origin.toLowerCase(Locale.ROOT))
        .collect(Collectors.toUnmodifiableSet());
  }

  @Bean
  WebMvcStreamableServerTransportProvider crateScopedMcpTransport(McpJsonMapper jsonMapper) {
    return provider(jsonMapper, CRATE_SCOPED_ENDPOINT, McpTransportContextFactory.crateScoped());
  }

  @Bean
  WebMvcStreamableServerTransportProvider globalMcpTransport(McpJsonMapper jsonMapper) {
    return provider(jsonMapper, GLOBAL_ENDPOINT, McpTransportContextFactory.global());
  }

  @Bean
  McpSyncServer crateScopedMcpServer(WebMvcStreamableServerTransportProvider crateScopedMcpTransport) {
    return server(crateScopedMcpTransport);
  }

  @Bean
  McpSyncServer globalMcpServer(WebMvcStreamableServerTransportProvider globalMcpTransport) {
    return server(globalMcpTransport);
  }

  @Bean
  RouterFunction<ServerResponse> crateScopedMcpRoutes(
      WebMvcStreamableServerTransportProvider crateScopedMcpTransport) {
    return crateScopedMcpTransport.getRouterFunction();
  }

  @Bean
  RouterFunction<ServerResponse> globalMcpRoutes(
      WebMvcStreamableServerTransportProvider globalMcpTransport) {
    return globalMcpTransport.getRouterFunction();
  }

  /** The SDK's Jackson binding, on the same Jackson 3 mapper the rest of the HTTP layer uses. */
  @Bean
  McpJsonMapper mcpJsonMapper() {
    return new JacksonMcpJsonMapper(JsonMapper.builder().build());
  }

  private WebMvcStreamableServerTransportProvider provider(
      McpJsonMapper jsonMapper, String endpoint, McpTransportContextExtractor<ServerRequest> context) {
    return WebMvcStreamableServerTransportProvider.builder()
        .jsonMapper(jsonMapper)
        .mcpEndpoint(endpoint)
        .contextExtractor(context)
        .securityValidator(this::validateOrigin)
        .build();
  }

  private McpSyncServer server(WebMvcStreamableServerTransportProvider provider) {
    return McpServer.sync(provider)
        .serverInfo("contextcrate", version)
        .instructions(catalog.instructions())
        .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
        .tools(adapter.specifications())
        // Run the tool on the calling thread. The adapter restores the security context anyway, so
        // this is belt and braces rather than the only defence.
        .immediateExecution(true)
        .build();
  }

  /**
   * Rejects a browser origin that has not been allow-listed, as the specification requires against
   * DNS rebinding. An empty list means "do not check": server-to-server clients send no {@code
   * Origin} at all, and treating the empty list as "allow nothing" locked out every client that did.
   */
  void validateOrigin(Map<String, List<String>> headers) throws ServerTransportSecurityException {
    if (allowedOrigins.isEmpty()) return;
    List<String> origins = headers.entrySet().stream()
        .filter(entry -> entry.getKey().equalsIgnoreCase("Origin"))
        .flatMap(entry -> entry.getValue().stream())
        .toList();
    for (String origin : origins)
      if (!origin.isBlank() && !allowedOrigins.contains(origin.trim().toLowerCase(Locale.ROOT)))
        throw new ServerTransportSecurityException(403, "Origin is not allowed: " + origin);
  }
}
