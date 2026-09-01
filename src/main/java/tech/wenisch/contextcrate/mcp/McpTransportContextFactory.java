package tech.wenisch.contextcrate.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.function.ServerRequest;

/**
 * Captures, on the request thread, the two things a tool needs later: which crate the endpoint
 * addresses and who is calling.
 *
 * <p>Both are thread-local at this point — the crate sits in the request's path variables and the
 * authentication in {@code SecurityContextHolder}, put there by {@code ApiKeyAuthenticationFilter}.
 * The tool may run on a different thread, so they are copied into the transport context, which the
 * SDK carries through to the handler.
 */
public final class McpTransportContextFactory {
  public static final String CRATE_ID = "contextcrate.crateId";
  public static final String AUTHENTICATION = "contextcrate.authentication";

  private McpTransportContextFactory() {}

  /** Extractor for the crate-scoped endpoint, which carries a {@code crateId} path variable. */
  public static McpTransportContextExtractor<ServerRequest> crateScoped() {
    return request -> context(crateId(request));
  }

  /** Extractor for the global endpoint, where the crate comes from the call or the credential. */
  public static McpTransportContextExtractor<ServerRequest> global() {
    return request -> context(null);
  }

  private static McpTransportContext context(UUID crateId) {
    Map<String, Object> values = new HashMap<>();
    if (crateId != null) values.put(CRATE_ID, crateId);
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null) values.put(AUTHENTICATION, authentication);
    return McpTransportContext.create(values);
  }

  private static UUID crateId(ServerRequest request) {
    try {
      String value = request.pathVariables().get("crateId");
      return value == null ? null : UUID.fromString(value);
    } catch (IllegalArgumentException notAUuid) {
      // A malformed crate id is reported by the resolver as a tool error, not as a transport fault.
      return null;
    }
  }
}
