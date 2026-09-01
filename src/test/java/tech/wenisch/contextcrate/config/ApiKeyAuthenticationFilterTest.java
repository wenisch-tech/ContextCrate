package tech.wenisch.contextcrate.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tech.wenisch.contextcrate.domain.ApiKey;
import tech.wenisch.contextcrate.domain.CrateMember;
import tech.wenisch.contextcrate.repository.ApiKeyRepository;
import tech.wenisch.contextcrate.security.ApiKeyPrincipal;
import tech.wenisch.contextcrate.storage.Hashing;

class ApiKeyAuthenticationFilterTest {
  private static final String TOKEN = "cc_a-token-value";

  private final ApiKeyRepository keys = mock(ApiKeyRepository.class);
  private final ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(keys);
  private final HttpServletRequest request = mock(HttpServletRequest.class);
  private final HttpServletResponse response = mock(HttpServletResponse.class);
  private final FilterChain chain = mock(FilterChain.class);

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private void keyExists() {
    ApiKey key = new ApiKey(UUID.randomUUID(), "mcp", "cc_a-token-", Hashing.sha256(TOKEN),
        ApiKey.KeyType.CRATE, null, UUID.randomUUID(), CrateMember.Role.VIEWER);
    when(keys.findByKeyHashAndRevokedFalse(Hashing.sha256(TOKEN))).thenReturn(Optional.of(key));
  }

  private ApiKeyPrincipal authenticatedPrincipal() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication == null ? null : (ApiKeyPrincipal) authentication.getPrincipal();
  }

  @Test
  void theApiKeyHeaderStillAuthenticates() throws Exception {
    keyExists();
    when(request.getHeader("X-API-KEY")).thenReturn(TOKEN);

    filter.doFilter(request, response, chain);

    assertThat(authenticatedPrincipal()).isNotNull();
    verify(chain).doFilter(request, response);
  }

  @Test
  void aBearerTokenAuthenticatesSoMcpClientsCanConnect() throws Exception {
    keyExists();
    when(request.getHeader("Authorization")).thenReturn("Bearer " + TOKEN);

    filter.doFilter(request, response, chain);

    assertThat(authenticatedPrincipal()).isNotNull();
  }

  @Test
  void theBearerPrefixIsMatchedCaseInsensitively() throws Exception {
    keyExists();
    when(request.getHeader("Authorization")).thenReturn("bearer " + TOKEN);

    filter.doFilter(request, response, chain);

    assertThat(authenticatedPrincipal()).isNotNull();
  }

  @Test
  void basicCredentialsAreNotMistakenForAToken() throws Exception {
    when(request.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNzd29yZA==");

    filter.doFilter(request, response, chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(keys, never()).findByKeyHashAndRevokedFalse(anyString());
    verify(chain).doFilter(request, response);
  }

  @Test
  void anEmptyBearerValueIsIgnored() throws Exception {
    when(request.getHeader("Authorization")).thenReturn("Bearer    ");

    filter.doFilter(request, response, chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(keys, never()).findByKeyHashAndRevokedFalse(anyString());
  }

  @Test
  void anExistingAuthenticationIsNotOverwritten() throws Exception {
    var existing = new UsernamePasswordAuthenticationToken("session-user", null, java.util.List.of());
    SecurityContextHolder.getContext().setAuthentication(existing);
    when(request.getHeader("Authorization")).thenReturn("Bearer " + TOKEN);

    filter.doFilter(request, response, chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(existing);
    verify(keys, never()).findByKeyHashAndRevokedFalse(anyString());
  }

  @Test
  void anUnknownTokenLeavesTheRequestUnauthenticated() throws Exception {
    when(request.getHeader("Authorization")).thenReturn("Bearer cc_not-a-real-token");
    when(keys.findByKeyHashAndRevokedFalse(anyString())).thenReturn(Optional.empty());

    filter.doFilter(request, response, chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(chain).doFilter(request, response);
  }
}
