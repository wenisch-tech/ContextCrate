package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "crate")
public class Crate {
  public enum Status { ACTIVE, ARCHIVING, ARCHIVED, PURGING, PURGE_FAILED }

  @Id private UUID id;
  @Column(nullable = false, length = 200) private String name;
  @Column(length = 2000) private String description;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private Status status;
  @Column(name = "created_by") private UUID createdBy;
  @Column(name = "active_index_generation", nullable = false) private int activeIndexGeneration;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected Crate() {}

  public Crate(UUID id, String name, String description, UUID createdBy) {
    this.id = id;
    this.name = requireName(name);
    this.description = blankToNull(description);
    this.createdBy = createdBy;
    this.status = Status.ACTIVE;
    this.activeIndexGeneration = 1;
    this.createdAt = Instant.now();
    this.updatedAt = createdAt;
  }

  public void update(String name, String description) {
    requireActive();
    this.name = requireName(name);
    this.description = blankToNull(description);
    this.updatedAt = Instant.now();
  }

  public void archiveRequested() { requireActive(); status = Status.ARCHIVING; updatedAt = Instant.now(); }
  public void archived() { status = Status.ARCHIVED; updatedAt = Instant.now(); }
  public void restore() {
    if (status != Status.ARCHIVED) throw new IllegalStateException("Only archived crates can be restored");
    status = Status.ACTIVE; updatedAt = Instant.now();
  }
  public void purging() {
    if (status != Status.ARCHIVED && status != Status.PURGE_FAILED)
      throw new IllegalStateException("Archive the crate before purging");
    status = Status.PURGING; updatedAt = Instant.now();
  }
  public void purgeFailed() { status = Status.PURGE_FAILED; updatedAt = Instant.now(); }
  public void activateGeneration(int generation) {
    if (generation < 1) throw new IllegalArgumentException("generation must be positive");
    activeIndexGeneration = generation; updatedAt = Instant.now();
  }
  public void requireActive() {
    if (status != Status.ACTIVE) throw new IllegalStateException("Crate is not active");
  }

  public UUID getId() { return id; }
  public String getName() { return name; }
  public String getDescription() { return description; }
  public Status getStatus() { return status; }
  public UUID getCreatedBy() { return createdBy; }
  public int getActiveIndexGeneration() { return activeIndexGeneration; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }

  private static String requireName(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("crate name is required");
    String trimmed = value.trim();
    if (trimmed.length() > 200) throw new IllegalArgumentException("crate name is too long");
    return trimmed;
  }
  private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
