package tech.wenisch.harvex.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_key")
public class ApiKey {
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

  protected ApiKey() {}

  public ApiKey(UUID id, String name, String prefix, String hash) {
    this.id = id;
    this.name = name;
    this.keyPrefix = prefix;
    this.keyHash = hash;
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

  public void revoke() {
    revoked = true;
  }
}
