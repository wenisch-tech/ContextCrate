package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "crawl_job")
public class CrawlJob {
  @Id private UUID id;

  @Column(name = "crate_id", nullable = false)
  private UUID crateId;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(name = "configuration_json", nullable = false, columnDefinition = "text")
  private String configurationJson;

  @Column(nullable = false)
  private boolean enabled;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected CrawlJob() {}

  public CrawlJob(UUID id, String name, String configurationJson) {
    this.id = id;
    this.crateId = CrateIds.LEGACY;
    this.name = name;
    this.configurationJson = configurationJson;
    this.enabled = true;
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

  public String getConfigurationJson() {
    return configurationJson;
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

  public void update(String name, String json, boolean enabled) {
    this.name = name;
    this.configurationJson = json;
    this.enabled = enabled;
    this.updatedAt = Instant.now();
  }
}
