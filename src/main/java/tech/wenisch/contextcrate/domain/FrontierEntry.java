package tech.wenisch.contextcrate.domain;

import static tech.wenisch.contextcrate.domain.PipelineTypes.FrontierStatus;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "frontier_entry",
    uniqueConstraints = @UniqueConstraint(columnNames = {"run_id", "canonical_url"}))
public class FrontierEntry {
  @Id private UUID id;

  @Column(name = "crate_id", nullable = false)
  private UUID crateId;

  @Column(name = "run_id", nullable = false)
  private UUID runId;

  @Column(nullable = false, length = 4096)
  private String url;

  @Column(name = "canonical_url", nullable = false, length = 4096)
  private String canonicalUrl;

  @Column(nullable = false)
  private int depth;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private FrontierStatus status;

  @Column(name = "discovered_at", nullable = false)
  private Instant discoveredAt;

  protected FrontierEntry() {}

  public FrontierEntry(UUID id, UUID runId, String url, String canonicalUrl, int depth) {
    this.id = id;
    this.crateId = CrateIds.LEGACY;
    this.runId = runId;
    this.url = url;
    this.canonicalUrl = canonicalUrl;
    this.depth = depth;
    this.status = FrontierStatus.PENDING;
    this.discoveredAt = Instant.now();
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

  public UUID getRunId() {
    return runId;
  }

  public String getUrl() {
    return url;
  }

  public String getCanonicalUrl() {
    return canonicalUrl;
  }

  public int getDepth() {
    return depth;
  }

  public FrontierStatus getStatus() {
    return status;
  }

  public void status(FrontierStatus status) {
    this.status = status;
  }
}
