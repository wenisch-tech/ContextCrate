package tech.wenisch.contextcrate.config;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KeycloakOidcUserServiceTest {
  @Test
  void mapsRealmRoleToGlobalAdministrator() {
    assertThat(KeycloakOidcUserService.hasContextCrateAdminRole(
        Map.of("realm_access", Map.of("roles", java.util.List.of("ContextCrate_Admin"))))).isTrue();
  }

  @Test
  void doesNotGrantAdministratorForAnUnrelatedRole() {
    assertThat(KeycloakOidcUserService.hasContextCrateAdminRole(
        Map.of("realm_access", Map.of("roles", java.util.List.of("user"))))).isFalse();
  }

  @Test
  void mapsTopLevelAndClientRolesToGlobalAdministrator() {
    assertThat(KeycloakOidcUserService.hasContextCrateAdminRole(
        Map.of("roles", java.util.List.of("ContextCrate_Admin")))).isTrue();
    assertThat(KeycloakOidcUserService.hasContextCrateAdminRole(
        Map.of("resource_access", Map.of("contextcrate",
            Map.of("roles", java.util.List.of("ContextCrate_Admin")))))).isTrue();
  }

  @Test
  void prefersEmailAndFallsBackToPreferredUsername() {
    assertThat(KeycloakOidcUserService.identifier(
        Map.of("email", "user@example.com", "preferred_username", "keycloak-user")))
        .isEqualTo("user@example.com");
    assertThat(KeycloakOidcUserService.identifier(Map.of("preferred_username", "keycloak-user")))
        .isEqualTo("keycloak-user");
  }
}
