package tech.wenisch.contextcrate.domain;

import static tech.wenisch.contextcrate.domain.PipelineTypes.RunStatus;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "ingestion_run")
public class IngestionRun {
  @Id private UUID id;
  @Column(name = "crate_id", nullable = false) private UUID crateId;
  @Column(name = "source_id", nullable = false) private UUID sourceId;
  @Column(name = "ingestion_job_id", nullable = false) private UUID ingestionJobId;
  @Enumerated(EnumType.STRING) @Column(nullable = false) private RunStatus status;
  @Column(name = "source_configuration_json", nullable = false, columnDefinition = "text")
  private String sourceConfigurationJson;
  @Column(name = "job_configuration_json", nullable = false, columnDefinition = "text")
  private String jobConfigurationJson;
  @Column(name = "resolved_revision", length = 255) private String resolvedRevision;
  @Column(name = "started_at", nullable = false) private Instant startedAt;
  @Column(name = "finished_at") private Instant finishedAt;

  protected IngestionRun() {}

  public IngestionRun(UUID id, UUID crateId, UUID sourceId, UUID jobId,
      String sourceJson, String jobJson) {
    this.id = Objects.requireNonNull(id);
    this.crateId = Objects.requireNonNull(crateId);
    this.sourceId = Objects.requireNonNull(sourceId);
    this.ingestionJobId = Objects.requireNonNull(jobId);
    this.sourceConfigurationJson = Objects.requireNonNull(sourceJson);
    this.jobConfigurationJson = Objects.requireNonNull(jobJson);
    this.status = RunStatus.RUNNING;
    this.startedAt = Instant.now();
  }

  public UUID getId() { return id; }
  public UUID getCrateId() { return crateId; }
  public UUID getSourceId() { return sourceId; }
  public UUID getIngestionJobId() { return ingestionJobId; }
  public RunStatus getStatus() { return status; }
  public String getSourceConfigurationJson() { return sourceConfigurationJson; }
  public String getJobConfigurationJson() { return jobConfigurationJson; }
  public String getResolvedRevision() { return resolvedRevision; }
  public Instant getStartedAt() { return startedAt; }
  public Instant getFinishedAt() { return finishedAt; }

  public void resolvedRevision(String value) { this.resolvedRevision = value; }
  public void status(RunStatus value) {
    this.status = value;
    if (value == RunStatus.COMPLETED || value == RunStatus.FAILED || value == RunStatus.CANCELLED)
      finishedAt = Instant.now();
  }
}
