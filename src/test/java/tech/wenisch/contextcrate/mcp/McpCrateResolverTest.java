package tech.wenisch.contextcrate.mcp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tech.wenisch.contextcrate.domain.Crate;
import tech.wenisch.contextcrate.domain.CrateMember;
import tech.wenisch.contextcrate.repository.CrateRepository;
import tech.wenisch.contextcrate.security.ApiKeyPrincipal;
import tech.wenisch.contextcrate.service.CrateAccessService;
import tech.wenisch.contextcrate.service.CrateService;

class McpCrateResolverTest {
  private final CrateService crates = mock(CrateService.class);
  private final CrateAccessService access = mock(CrateAccessService.class);
  private final CrateRepository repository = mock(CrateRepository.class);
  private final McpCrateResolver resolver = new McpCrateResolver(crates, access, repository);

  private final Crate bound = new Crate(UUID.randomUUID(), "Product docs", null, UUID.randomUUID());
  private final Crate other = new Crate(UUID.randomUUID(), "Internal wiki", null, UUID.randomUUID());

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private void authenticateWithCrateKey(UUID crateId) {
    var principal = new ApiKeyPrincipal("api-key:mcp", null, crateId, CrateMember.Role.VIEWER);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
  }

  private void authenticateWithPersonalKey() {
    var principal = new ApiKeyPrincipal("api-key:me", UUID.randomUUID(), null, null);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
  }

  @Test
  void aCrateScopedKeyResolvesToItsBoundCrateEvenThoughMembershipsAreEmpty() {
    // CrateAccessService.memberships() returns nothing for crate-scoped keys, so the crate has to
    // come off the principal — this is the credential an external AI client is normally given.
    authenticateWithCrateKey(bound.getId());
    when(repository.findById(bound.getId())).thenReturn(Optional.of(bound));

    assertThat(resolver.resolve(null, null)).isEqualTo(bound);
    verify(access).require(bound.getId(), CrateMember.Role.VIEWER);
    verify(crates, never()).accessible();
  }

  @Test
  void aCrateScopedKeyListsOnlyItsOwnCrate() {
    authenticateWithCrateKey(bound.getId());
    when(repository.findById(bound.getId())).thenReturn(Optional.of(bound));

    assertThat(resolver.available()).containsExactly(bound);
    verify(crates, never()).accessible();
  }

  @Test
  void thePathCrateAlwaysWinsAndIsStillAuthorized() {
    when(repository.findById(other.getId())).thenReturn(Optional.of(other));

    assertThat(resolver.resolve(other.getId(), null)).isEqualTo(other);
    verify(access).require(other.getId(), CrateMember.Role.VIEWER);
  }

  @Test
  void aForeignCrateIsRejectedByTheAccessGate() {
    doThrow(new AccessDeniedException("API key cannot access this crate"))
        .when(access).require(other.getId(), CrateMember.Role.VIEWER);

    assertThatThrownBy(() -> resolver.resolve(other.getId(), null))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void aSingleAccessibleCrateIsChosenWithoutAnArgument() {
    authenticateWithPersonalKey();
    when(crates.accessible()).thenReturn(List.of(bound));
    when(repository.findById(bound.getId())).thenReturn(Optional.of(bound));

    assertThat(resolver.resolve(null, null)).isEqualTo(bound);
  }

  @Test
  void severalAccessibleCratesRequireAChoiceAndPointAtListCrates() {
    authenticateWithPersonalKey();
    when(crates.accessible()).thenReturn(List.of(bound, other));

    assertThatThrownBy(() -> resolver.resolve(null, null))
        .isInstanceOf(McpCrateResolver.UnresolvedCrateException.class)
        .hasMessageContaining("list_crates");
  }

  @Test
  void aCrateCanBeChosenByNameCaseInsensitively() {
    authenticateWithPersonalKey();
    when(crates.accessible()).thenReturn(List.of(bound, other));
    when(repository.findById(other.getId())).thenReturn(Optional.of(other));

    assertThat(resolver.resolve(null, "internal WIKI")).isEqualTo(other);
  }

  @Test
  void anUnknownCrateNameIsReportedHelpfully() {
    authenticateWithPersonalKey();
    when(crates.accessible()).thenReturn(List.of(bound));

    assertThatThrownBy(() -> resolver.resolve(null, "Nonexistent"))
        .isInstanceOf(McpCrateResolver.UnresolvedCrateException.class)
        .hasMessageContaining("Nonexistent");
  }

  @Test
  void noAccessibleCrateIsStatedPlainly() {
    authenticateWithPersonalKey();
    when(crates.accessible()).thenReturn(List.of());

    assertThatThrownBy(() -> resolver.resolve(null, null))
        .isInstanceOf(McpCrateResolver.UnresolvedCrateException.class)
        .hasMessageContaining("No crate is accessible");
  }
}
