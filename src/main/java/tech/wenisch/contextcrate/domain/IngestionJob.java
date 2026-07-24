package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "ingestion_job")
public class IngestionJob {
  @Id private UUID id;
  @Column(name = "crate_id", nullable = false) private UUID crateId;
  @Column(name = "source_id", nullable = false) private UUID sourceId;
  @Column(nullable = false, length = 200) private String name;
  @Column(name = "configuration_json", nullable = false, columnDefinition = "text")
  private String configurationJson;
  @Column(nullable = false) private boolean enabled;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected IngestionJob() {}

  public IngestionJob(UUID id, UUID crateId, UUID sourceId, String name, String configurationJson) {
    this.id = Objects.requireNonNull(id);
    this.crateId = Objects.requireNonNull(crateId);
    this.sourceId = Objects.requireNonNull(sourceId);
    this.name = Objects.requireNonNull(name);
    this.configurationJson = Objects.requireNonNull(configurationJson);
    this.enabled = true;
    this.createdAt = Instant.now();
    this.updatedAt = createdAt;
  }

  public UUID getId() { return id; }
  public UUID getCrateId() { return crateId; }
  public UUID getSourceId() { return sourceId; }
  public String getName() { return name; }
  public String getConfigurationJson() { return configurationJson; }
  public boolean isEnabled() { return enabled; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }

  public void update(String name, String json, boolean enabled) {
    this.name = Objects.requireNonNull(name);
    this.configurationJson = Objects.requireNonNull(json);
    this.enabled = enabled;
    this.updatedAt = Instant.now();
  }
}
