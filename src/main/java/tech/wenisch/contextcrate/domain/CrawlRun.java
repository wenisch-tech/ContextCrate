package tech.wenisch.contextcrate.domain;

import static tech.wenisch.contextcrate.domain.PipelineTypes.RunStatus;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "crawl_run")
public class CrawlRun {
  @Id private UUID id;

  @Column(name = "crate_id", nullable = false)
  private UUID crateId;

  @Column(name = "job_id", nullable = false)
  private UUID jobId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private RunStatus status;

  @Column(name = "configuration_json", nullable = false, columnDefinition = "text")
  private String configurationJson;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "finished_at")
  private Instant finishedAt;

  protected CrawlRun() {}

  public CrawlRun(UUID id, UUID jobId, String json) {
    this.id = id;
    this.crateId = CrateIds.LEGACY;
    this.jobId = jobId;
    this.configurationJson = json;
    this.status = RunStatus.RUNNING;
    this.startedAt = Instant.now();
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

  public UUID getJobId() {
    return jobId;
  }

  public RunStatus getStatus() {
    return status;
  }

  public String getConfigurationJson() {
    return configurationJson;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getFinishedAt() {
    return finishedAt;
  }

  public void status(RunStatus value) {
    this.status = value;
    if (value == RunStatus.COMPLETED || value == RunStatus.FAILED || value == RunStatus.CANCELLED)
      finishedAt = Instant.now();
  }
}
