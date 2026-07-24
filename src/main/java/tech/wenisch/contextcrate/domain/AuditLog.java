package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_log")
public class AuditLog {
  @Id private UUID id;

  @Column(name = "crate_id", nullable = false)
  private UUID crateId;

  @Column(nullable = false, length = 320)
  private String actor;

  @Column(nullable = false, length = 100)
  private String action;

  @Column(nullable = false, length = 500)
  private String subject;

  @Column(nullable = false, columnDefinition = "text")
  private String details;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected AuditLog() {}

  public AuditLog(String actor, String action, String subject, String details) {
    this.id = UUID.randomUUID();
    this.crateId = CrateIds.LEGACY;
    this.actor = actor;
    this.action = action;
    this.subject = subject;
    this.details = details;
    this.createdAt = Instant.now();
  }

  public AuditLog(UUID crateId, String actor, String action, String subject, String details) {
    this(actor, action, subject, details);
    assignCrate(crateId);
  }

  public UUID getCrateId() {
    return crateId;
  }

  public void assignCrate(UUID crateId) {
    this.crateId = java.util.Objects.requireNonNull(crateId);
  }

  public UUID getId() {
    return id;
  }

  public String getActor() {
    return actor;
  }

  public String getAction() {
    return action;
  }

  public String getSubject() {
    return subject;
  }

  public String getDetails() {
    return details;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
