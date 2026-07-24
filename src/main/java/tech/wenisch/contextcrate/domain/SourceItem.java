package tech.wenisch.contextcrate.domain;

import static tech.wenisch.contextcrate.domain.PipelineTypes.FrontierStatus;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "source_item",
    uniqueConstraints = @UniqueConstraint(columnNames = {"run_id", "source_uri"}))
public class SourceItem {
  @Id private UUID id;
  @Column(name = "crate_id", nullable = false) private UUID crateId;
  @Column(name = "run_id", nullable = false) private UUID runId;
  @Column(nullable = false, length = 4096) private String locator;
  @Column(name = "source_uri", nullable = false, length = 4096) private String sourceUri;
  @Column(nullable = false) private int depth;
  @Enumerated(EnumType.STRING) @Column(nullable = false) private FrontierStatus status;
  @Column(name = "discovered_at", nullable = false) private Instant discoveredAt;

  protected SourceItem() {}

  public SourceItem(UUID id, UUID runId, String locator, String sourceUri, int depth) {
    this.id = Objects.requireNonNull(id);
    this.crateId = CrateIds.LEGACY;
    this.runId = Objects.requireNonNull(runId);
    this.locator = Objects.requireNonNull(locator);
    this.sourceUri = Objects.requireNonNull(sourceUri);
    this.depth = depth;
    this.status = FrontierStatus.PENDING;
    this.discoveredAt = Instant.now();
  }

  public UUID getId() { return id; }
  public UUID getCrateId() { return crateId; }
  public UUID getRunId() { return runId; }
  public String getLocator() { return locator; }
  public String getSourceUri() { return sourceUri; }
  public int getDepth() { return depth; }
  public FrontierStatus getStatus() { return status; }
  public Instant getDiscoveredAt() { return discoveredAt; }
  public void assignCrate(UUID value) { crateId = Objects.requireNonNull(value); }
  public void status(FrontierStatus value) { status = value; }
}
