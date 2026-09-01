package tech.wenisch.contextcrate.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

  @Column(name = "can_create_crates", nullable = false)
  private boolean canCreateCrates;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected AppUser() {}

  public AppUser(UUID id, String email, String hash) {
    this(id, email, hash, "ADMIN", true);
  }

  public AppUser(UUID id, String email, String hash, String role, boolean passwordChangeRequired) {
    this.id = id;
    this.email = email.trim().toLowerCase(java.util.Locale.ROOT);
    this.passwordHash = hash;
    this.role = role;
    this.passwordChangeRequired = passwordChangeRequired;
    this.enabled = true;
    this.canCreateCrates = false;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  @JsonIgnore
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

  public boolean isCanCreateCrates() {
    return canCreateCrates;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void canCreateCrates(boolean value) {
    canCreateCrates = value;
  }

  public void changePassword(String hash) {
    passwordHash = hash;
    passwordChangeRequired = false;
  }

  /** Sets an administrator-issued temporary password that must be replaced at the next sign-in. */
  public void resetPassword(String hash) {
    passwordHash = hash;
    passwordChangeRequired = true;
  }

  public void enabled(boolean value) {
    enabled = value;
  }

  /** Updates the installation role asserted by the configured identity provider. */
  public void role(String value) {
    role = value;
  }
}
