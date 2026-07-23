package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "admin_elevation")
public class AdminElevation {
  @Id private UUID id;
  @Column(name = "admin_user_id", nullable = false) private UUID adminUserId;
  @Column(name = "crate_id", nullable = false) private UUID crateId;
  @Column(nullable = false, length = 1000) private String reason;
  @Column(name = "started_at", nullable = false) private Instant startedAt;
  @Column(name = "expires_at", nullable = false) private Instant expiresAt;
  @Column(name = "ended_at") private Instant endedAt;

  protected AdminElevation() {}
  public AdminElevation(UUID adminUserId, UUID crateId, String reason) {
    if (reason == null || reason.isBlank()) throw new IllegalArgumentException("elevation reason is required");
    id = UUID.randomUUID(); this.adminUserId = adminUserId; this.crateId = crateId;
    this.reason = reason.trim(); startedAt = Instant.now(); expiresAt = startedAt.plusSeconds(30 * 60);
  }
  public void end() { if (endedAt == null) endedAt = Instant.now(); }
  public boolean activeAt(Instant now) { return endedAt == null && expiresAt.isAfter(now); }
  public UUID getId() { return id; }
  public UUID getAdminUserId() { return adminUserId; }
  public UUID getCrateId() { return crateId; }
  public String getReason() { return reason; }
  public Instant getStartedAt() { return startedAt; }
  public Instant getExpiresAt() { return expiresAt; }
  public Instant getEndedAt() { return endedAt; }
}
