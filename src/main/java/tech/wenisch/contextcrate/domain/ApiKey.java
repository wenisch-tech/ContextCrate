package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_key")
public class ApiKey {
  public enum KeyType { PERSONAL, CRATE }

  @Id private UUID id;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(name = "key_prefix", nullable = false, length = 16)
  private String keyPrefix;

  @Column(name = "key_hash", nullable = false, unique = true, length = 64)
  private String keyHash;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private boolean revoked;

  @Enumerated(EnumType.STRING)
  @Column(name = "key_type", nullable = false, length = 16)
  private KeyType keyType;

  @Column(name = "user_id")
  private UUID userId;

  @Column(name = "crate_id")
  private UUID crateId;

  @Enumerated(EnumType.STRING)
  @Column(name = "crate_role", length = 16)
  private CrateMember.Role crateRole;

  protected ApiKey() {}

  public ApiKey(UUID id, String name, String prefix, String hash) {
    this(id, name, prefix, hash, KeyType.CRATE, null, CrateIds.LEGACY, CrateMember.Role.EDITOR);
  }

  public ApiKey(
      UUID id, String name, String prefix, String hash, KeyType keyType,
      UUID userId, UUID crateId, CrateMember.Role crateRole) {
    this.id = id;
    this.name = name;
    this.keyPrefix = prefix;
    this.keyHash = hash;
    this.keyType = keyType;
    this.userId = userId;
    this.crateId = crateId;
    this.crateRole = crateRole;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getKeyPrefix() {
    return keyPrefix;
  }

  public String getKeyHash() {
    return keyHash;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public boolean isRevoked() {
    return revoked;
  }

  public KeyType getKeyType() { return keyType; }
  public UUID getUserId() { return userId; }
  public UUID getCrateId() { return crateId; }
  public CrateMember.Role getCrateRole() { return crateRole; }

  public void revoke() {
    revoked = true;
  }
}
