package tech.wenisch.contextcrate.domain;

import static tech.wenisch.contextcrate.domain.PipelineTypes.ExtractionType;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "extraction_rule")
public class ExtractionRule {
  @Id private UUID id;

  @Column(name = "crate_id", nullable = false)
  private UUID crateId;

  @Column(nullable = false, length = 200)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ExtractionType type;

  @Column(columnDefinition = "text")
  private String pattern;

  @Column(nullable = false)
  private boolean enabled;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected ExtractionRule() {}

  public ExtractionRule(UUID id, String name, ExtractionType type, String pattern, boolean enabled) {
    this.id = id;
    this.crateId = CrateIds.LEGACY;
    this.name = name;
    this.type = type;
    this.pattern = pattern;
    this.enabled = enabled;
    this.createdAt = Instant.now();
    this.updatedAt = createdAt;
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

  public String getName() {
    return name;
  }

  public ExtractionType getType() {
    return type;
  }

  public String getPattern() {
    return pattern;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void update(String name, ExtractionType type, String pattern, boolean enabled) {
    this.name = name;
    this.type = type;
    this.pattern = pattern;
    this.enabled = enabled;
    this.updatedAt = Instant.now();
  }
}