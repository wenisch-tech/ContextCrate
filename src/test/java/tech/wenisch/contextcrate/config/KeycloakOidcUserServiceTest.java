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
}
