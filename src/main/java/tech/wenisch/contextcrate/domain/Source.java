package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "source")
public class Source {
  @Id private UUID id;
  @Column(name = "crate_id", nullable = false) private UUID crateId;
  @Column(nullable = false, length = 200) private String name;
  @Column(length = 2000) private String description;
  @Enumerated(EnumType.STRING)
  @Column(name = "connector_type", nullable = false, length = 32)
  private ConnectorType connectorType;
  @Column(name = "configuration_json", nullable = false, columnDefinition = "text")
  private String configurationJson;
  @Column(nullable = false) private boolean enabled;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected Source() {}

  public Source(UUID id, UUID crateId, String name, String description,
      ConnectorType connectorType, String configurationJson) {
    this.id = Objects.requireNonNull(id);
    this.crateId = Objects.requireNonNull(crateId);
    this.name = Objects.requireNonNull(name);
    this.description = blankToNull(description);
    this.connectorType = Objects.requireNonNull(connectorType);
    this.configurationJson = Objects.requireNonNull(configurationJson);
    this.enabled = true;
    this.createdAt = Instant.now();
    this.updatedAt = createdAt;
  }

  public UUID getId() { return id; }
  public UUID getCrateId() { return crateId; }
  public String getName() { return name; }
  public String getDescription() { return description; }
  public ConnectorType getConnectorType() { return connectorType; }
  public String getConfigurationJson() { return configurationJson; }
  public boolean isEnabled() { return enabled; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }

  public void update(String name, String description, String configurationJson, boolean enabled) {
    this.name = Objects.requireNonNull(name);
    this.description = blankToNull(description);
    this.configurationJson = Objects.requireNonNull(configurationJson);
    this.enabled = enabled;
    this.updatedAt = Instant.now();
  }
  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
