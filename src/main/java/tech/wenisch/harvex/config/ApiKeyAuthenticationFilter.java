package tech.wenisch.harvex.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tech.wenisch.harvex.repository.ApiKeyRepository;
import tech.wenisch.harvex.storage.Hashing;

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
    String token = request.getHeader("X-API-KEY");
    if (token != null && SecurityContextHolder.getContext().getAuthentication() == null)
      keys.findByKeyHashAndRevokedFalse(Hashing.sha256(token))
          .ifPresent(
              key ->
                  SecurityContextHolder.getContext()
                      .setAuthentication(
                          new UsernamePasswordAuthenticationToken(
                              "api-key:" + key.getName(),
                              token,
                              List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))));
    chain.doFilter(request, response);
  }
}
