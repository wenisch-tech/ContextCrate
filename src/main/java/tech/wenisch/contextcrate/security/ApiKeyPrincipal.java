package tech.wenisch.contextcrate.security;

import java.util.UUID;
import tech.wenisch.contextcrate.domain.CrateMember;

public record ApiKeyPrincipal(
    String name, UUID userId, UUID crateId, CrateMember.Role crateRole, UUID keyId, String keyType) {
  public ApiKeyPrincipal(String name, UUID userId, UUID crateId, CrateMember.Role crateRole) {
    this(name, userId, crateId, crateRole, null, null);
  }
  public boolean personal() { return userId != null; }
}
