package tech.wenisch.harvex.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class AppUser {
  @Id private UUID id;

  @Column(nullable = false, unique = true, length = 320)
  private String email;

  @Column(name = "password_hash", nullable = false, length = 100)
  private String passwordHash;

  @Column(nullable = false, length = 32)
  private String role;

  @Column(name = "password_change_required", nullable = false)
  private boolean passwordChangeRequired;

  @Column(nullable = false)
  private boolean enabled;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected AppUser() {}

  public AppUser(UUID id, String email, String hash) {
    this.id = id;
    this.email = email;
    this.passwordHash = hash;
    this.role = "ADMIN";
    this.passwordChangeRequired = true;
    this.enabled = true;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public String getRole() {
    return role;
  }

  public boolean isPasswordChangeRequired() {
    return passwordChangeRequired;
  }

  public boolean isEnabled() {
    return enabled;
  }
}
