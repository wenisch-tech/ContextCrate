package tech.wenisch.contextcrate.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tech.wenisch.contextcrate.repository.ApiKeyRepository;
import tech.wenisch.contextcrate.security.ApiKeyPrincipal;
import tech.wenisch.contextcrate.storage.Hashing;

@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {
  private final ApiKeyRepository keys;

  public ApiKeyAuthenticationFilter(ApiKeyRepository keys) {
    this.keys = keys;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String token = token(request);
    if (token != null && SecurityContextHolder.getContext().getAuthentication() == null)
      keys.findByKeyHashAndRevokedFalse(Hashing.sha256(token))
          .ifPresent(
              key ->
                  SecurityContextHolder.getContext()
                      .setAuthentication(
                          new UsernamePasswordAuthenticationToken(
                              new ApiKeyPrincipal(
                                  "api-key:" + key.getName(),
                                  key.getUserId(),
                                  key.getCrateId(),
                                  key.getCrateRole()),
                              token,
                              List.of(
                                  new SimpleGrantedAuthority(
                                      key.getKeyType() == tech.wenisch.contextcrate.domain.ApiKey.KeyType.PERSONAL
                                          ? "ROLE_USER" : "ROLE_CRATE_KEY")))));
    chain.doFilter(request, response);
  }

  /**
   * Reads the API key from {@code X-API-KEY}, falling back to a {@code Bearer} authorization header.
   * MCP clients send the bearer form. {@code Basic} credentials are deliberately left alone so that
   * the HTTP-Basic authentication configured alongside this filter keeps working.
   */
  private static String token(HttpServletRequest request) {
    String header = request.getHeader("X-API-KEY");
    if (header != null && !header.isBlank()) return header.trim();
    String authorization = request.getHeader("Authorization");
    if (authorization == null) return null;
    String prefix = "Bearer ";
    if (authorization.length() <= prefix.length()
        || !authorization.regionMatches(true, 0, prefix, 0, prefix.length())) return null;
    String bearer = authorization.substring(prefix.length()).trim();
    return bearer.isEmpty() ? null : bearer;
  }
}
