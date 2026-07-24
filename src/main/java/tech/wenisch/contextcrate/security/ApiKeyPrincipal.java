package tech.wenisch.contextcrate.security;

import java.util.UUID;
import tech.wenisch.contextcrate.domain.CrateMember;

public record ApiKeyPrincipal(
    String name, UUID userId, UUID crateId, CrateMember.Role crateRole) {
  public boolean personal() { return userId != null; }
}
