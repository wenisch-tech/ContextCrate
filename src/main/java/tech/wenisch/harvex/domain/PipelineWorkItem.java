package tech.wenisch.harvex.domain;

import static tech.wenisch.harvex.domain.PipelineTypes.*;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "pipeline_work_item",
    uniqueConstraints = @UniqueConstraint(columnNames = {"stage", "idempotency_key"}))
public class PipelineWorkItem {
  @Id private UUID id;

  @Column(name = "schema_version", nullable = false)
  private int schemaVersion;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private WorkStage stage;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private WorkStatus status;

  @Column(nullable = false, columnDefinition = "text")
  private String payload;

  @Column(name = "correlation_id", nullable = false)
  private UUID correlationId;

  @Column(name = "idempotency_key", nullable = false, length = 500)
  private String idempotencyKey;

  @Column(nullable = false)
  private int priority;

  @Column(nullable = false)
  private int attempts;

  @Column(name = "available_at", nullable = false)
  private Instant availableAt;

  @Column(name = "lease_until")
  private Instant leaseUntil;

  @Column(name = "last_error", columnDefinition = "text")
  private String lastError;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected PipelineWorkItem() {}

  public PipelineWorkItem(
      UUID id,
      WorkStage stage,
      String payload,
      UUID correlationId,
      String idempotencyKey,
      int priority) {
    this.id = id;
    this.schemaVersion = 1;
    this.stage = stage;
    this.status = WorkStatus.PENDING;
    this.payload = payload;
    this.correlationId = correlationId;
    this.idempotencyKey = idempotencyKey;
    this.priority = priority;
    this.attempts = 0;
    this.availableAt = Instant.now();
    this.createdAt = availableAt;
    this.updatedAt = availableAt;
  }

  public UUID getId() {
    return id;
  }

  public int getSchemaVersion() {
    return schemaVersion;
  }

  public WorkStage getStage() {
    return stage;
  }

  public WorkStatus getStatus() {
    return status;
  }

  public String getPayload() {
    return payload;
  }

  public UUID getCorrelationId() {
    return correlationId;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public int getPriority() {
    return priority;
  }

  public int getAttempts() {
    return attempts;
  }

  public Instant getAvailableAt() {
    return availableAt;
  }

  public Instant getLeaseUntil() {
    return leaseUntil;
  }

  public String getLastError() {
    return lastError;
  }

  public void claim(Instant until) {
    status = WorkStatus.PROCESSING;
    leaseUntil = until;
    attempts++;
    updatedAt = Instant.now();
  }

  public void complete() {
    status = WorkStatus.COMPLETED;
    leaseUntil = null;
    updatedAt = Instant.now();
  }

  public void retry(Instant at, String error) {
    status = WorkStatus.RETRY_WAITING;
    availableAt = at;
    leaseUntil = null;
    lastError = error;
    updatedAt = Instant.now();
  }

  public void deadLetter(String error) {
    status = WorkStatus.DEAD_LETTERED;
    leaseUntil = null;
    lastError = error;
    updatedAt = Instant.now();
  }

  public void requeue() {
    status = WorkStatus.PENDING;
    availableAt = Instant.now();
    leaseUntil = null;
    lastError = null;
    updatedAt = Instant.now();
  }
}
