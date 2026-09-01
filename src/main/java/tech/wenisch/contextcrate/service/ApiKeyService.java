package tech.wenisch.contextcrate.service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tech.wenisch.contextcrate.domain.ApiKey;
import tech.wenisch.contextcrate.domain.CrateMember;
import tech.wenisch.contextcrate.repository.ApiKeyRepository;
import tech.wenisch.contextcrate.storage.Hashing;

/** Shared token generation/listing/revocation logic for both the JSON API and the web UI. */
@Service
public class ApiKeyService {
  private final ApiKeyRepository keys;
  private final SecureRandom random = new SecureRandom();

  public ApiKeyService(ApiKeyRepository keys) {
    this.keys = keys;
  }

  public List<ApiKey> personalKeys(UUID userId) {
    return keys.findByUserIdOrderByCreatedAtDesc(userId);
  }

  public List<ApiKey> crateKeys(UUID crateId) {
    return keys.findByCrateIdOrderByCreatedAtDesc(crateId);
  }

  public CreatedKey createPersonal(UUID userId, String name) {
    return create(name, ApiKey.KeyType.PERSONAL, userId, null, null);
  }

  public CreatedKey createCrate(UUID crateId, String name, CrateMember.Role role) {
    if (role == CrateMember.Role.OWNER)
      throw new IllegalArgumentException("Crate API keys may be Viewer or Editor");
    return create(name, ApiKey.KeyType.CRATE, null, crateId, role);
  }

  private CreatedKey create(
      String name, ApiKey.KeyType type, UUID userId, UUID crateId, CrateMember.Role role) {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
    byte[] secret = new byte[32];
    random.nextBytes(secret);
    // cc_ prefix lets downstream log scrubbers and secret scanners recognize the token shape.
    String token = "cc_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    ApiKey key =
        keys.save(
            new ApiKey(
                UUID.randomUUID(), name, token.substring(0, 12), Hashing.sha256(token), type,
                userId, crateId, role));
    return new CreatedKey(key.getId(), key.getName(), token);
  }

  public void revokePersonal(UUID userId, UUID id) {
    ApiKey key = keys.findById(id).orElseThrow();
    if (key.getKeyType() != ApiKey.KeyType.PERSONAL || !userId.equals(key.getUserId()))
      throw new AccessDeniedException("Key belongs to another principal");
    key.revoke();
    keys.save(key);
  }

  public void revokeCrate(UUID crateId, UUID id) {
    ApiKey key = keys.findById(id).orElseThrow();
    if (!crateId.equals(key.getCrateId()))
      throw new AccessDeniedException("Key belongs to another crate");
    key.revoke();
    keys.save(key);
  }

  public record CreatedKey(UUID id, String name, String token) {}
}
