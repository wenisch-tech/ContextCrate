package tech.wenisch.contextcrate.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tech.wenisch.contextcrate.domain.CrateMember;
import tech.wenisch.contextcrate.service.CrateAccessService;

/**
 * Refuses a connection to a crate the caller may not read.
 *
 * <p>The protocol handshake itself runs no tool, so without this an API key bound to one crate could
 * complete {@code initialize} against any other crate's endpoint and read the tool list. No content
 * would leak — every tool re-checks through {@link McpCrateResolver} — but the connection ought to
 * fail immediately and visibly rather than only when the first tool is called.
 *
 * <p>Registered inside the security filter chain so that a denial is turned into the API's 403 by
 * Spring Security's handler instead of surfacing as a server error.
 */
@Component
public class McpCrateAccessFilter extends OncePerRequestFilter {
  private static final Pattern CRATE_SCOPED_MCP =
      Pattern.compile("^/api/v1/crates/([^/]+)/mcp/?$");

  private final CrateAccessService access;

  public McpCrateAccessFilter(CrateAccessService access) {
    this.access = access;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Matcher matcher = CRATE_SCOPED_MCP.matcher(request.getRequestURI());
    if (matcher.matches()) {
      UUID crateId = crateId(matcher.group(1));
      // An unparseable id is left to the router, which answers 404 for a path that matches nothing.
      if (crateId != null) access.require(crateId, CrateMember.Role.VIEWER);
    }
    chain.doFilter(request, response);
  }

  private static UUID crateId(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException notAUuid) {
      return null;
    }
  }
}
