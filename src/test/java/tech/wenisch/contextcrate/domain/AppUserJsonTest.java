package tech.wenisch.contextcrate.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AppUserJsonTest {
  @Test
  void thePasswordHashIsNeverSerialized() {
    AppUser user = new AppUser(UUID.randomUUID(), "admin@example.com", "{bcrypt}$2a$10$secret",
        "ADMIN", true);

    String json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(user);

    assertThat(json).contains("admin@example.com").contains("\"role\":\"ADMIN\"");
    assertThat(json).doesNotContain("passwordHash").doesNotContain("bcrypt");
  }
}
