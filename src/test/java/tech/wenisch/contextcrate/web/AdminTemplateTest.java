package tech.wenisch.contextcrate.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AdminTemplateTest {
  private static String template(String name) throws IOException {
    try (var input = AdminTemplateTest.class.getResourceAsStream("/templates/" + name)) {
      assertThat(input).as(name).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void administrationEntryPointsAreGuardedByTheAdminFlag() throws IOException {
    assertThat(template("crates.html"))
        .contains("<a th:if=\"${isAdmin}\" class=\"card panel admin-tile mb-4\" href=\"/admin\">");
    assertThat(template("fragments.html"))
        .contains("th:if=\"${isAdmin}\" href=\"/admin\"")
        .contains("Crate management", "Personal API keys");
  }

  @Test
  void theNavigationFragmentToleratesPagesWithoutACrate() throws IOException {
    String fragments = template("fragments.html");

    assertThat(fragments).contains("th:if=\"${crate != null}\"");
    assertThat(fragments)
        .contains("th:href=\"${crate == null ? '/crates' : '/crates/' + crate.id}\"")
        .contains("id=${crate?.id}");
  }

  @Test
  void selfTargetedUserActionsAreDisabled() throws IOException {
    String admin = template("admin.html");

    assertThat(admin)
        .contains("th:disabled=\"${user.id == currentUserId}\"")
        .contains("You cannot change your own role")
        .contains("You cannot disable your own account");
  }

  @Test
  void everyStateChangingFormCarriesACsrfToken() throws IOException {
    String admin = template("admin.html");
    int forms = admin.split("method=\"post\"", -1).length - 1;
    int tokens = admin.split("th:name=\"\\$\\{_csrf.parameterName}\"", -1).length - 1;

    assertThat(forms).isPositive();
    assertThat(tokens).isEqualTo(forms);
  }

  @Test
  void datesAreFormattedOnTheCardBecauseTheTemporalsDialectIsAbsent() throws IOException {
    assertThat(template("crates.html")).contains("${card.updatedOn()}").doesNotContain("#temporals");
    assertThat(template("admin.html")).doesNotContain("#temporals");
  }
}
