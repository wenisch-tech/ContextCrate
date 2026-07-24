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
    String token = request.getHeader("X-API-KEY");
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
}
