package tech.wenisch.contextcrate.domain;

import static tech.wenisch.contextcrate.domain.PipelineTypes.FetchOutcome;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "acquisition_record")
public class AcquisitionRecord {
  @Id private UUID id;
  @Column(name = "crate_id", nullable = false) private UUID crateId;
  @Column(name = "run_id", nullable = false) private UUID runId;
  @Column(name = "source_item_id", nullable = false) private UUID sourceItemId;
  @Column(name = "requested_locator", nullable = false, length = 4096)
  private String requestedLocator;
  @Column(name = "final_locator", length = 4096) private String finalLocator;
  @Column(name = "status_code") private Integer statusCode;
  @Column(name = "content_type") private String contentType;
  private String charset;
  @Column(name = "artifact_key", length = 1024) private String artifactKey;
  @Column(name = "artifact_sha256", length = 64) private String artifactSha256;
  @Column(name = "artifact_length") private Long artifactLength;
  @Column(name = "duration_ms") private Long durationMs;
  @Enumerated(EnumType.STRING) @Column(nullable = false) private FetchOutcome outcome;
  @Column(name = "fetched_at", nullable = false) private Instant fetchedAt;
  @Column(name = "error_message", columnDefinition = "text") private String errorMessage;

  protected AcquisitionRecord() {}

  public AcquisitionRecord(UUID id, UUID runId, UUID sourceItemId, String requestedLocator) {
    this.id = Objects.requireNonNull(id);
    this.crateId = CrateIds.LEGACY;
    this.runId = Objects.requireNonNull(runId);
    this.sourceItemId = Objects.requireNonNull(sourceItemId);
    this.requestedLocator = Objects.requireNonNull(requestedLocator);
    this.outcome = FetchOutcome.FAILED;
    this.fetchedAt = Instant.now();
  }

  public void success(String finalLocator, int status, String type, String charset, String key,
      String sha, long length, long duration) {
    this.finalLocator = finalLocator;
    this.statusCode = status;
    this.contentType = type;
    this.charset = charset;
    this.artifactKey = key;
    this.artifactSha256 = sha;
    this.artifactLength = length;
    this.durationMs = duration;
    this.outcome = status >= 200 && status < 400 ? FetchOutcome.SUCCEEDED : FetchOutcome.HTTP_ERROR;
  }

  public void failure(FetchOutcome value, String message) {
    outcome = value;
    errorMessage = message;
  }

  public UUID getId() { return id; }
  public UUID getCrateId() { return crateId; }
  public UUID getRunId() { return runId; }
  public UUID getSourceItemId() { return sourceItemId; }
  public String getRequestedLocator() { return requestedLocator; }
  public String getFinalLocator() { return finalLocator; }
  public Integer getStatusCode() { return statusCode; }
  public String getContentType() { return contentType; }
  public String getCharset() { return charset; }
  public String getArtifactKey() { return artifactKey; }
  public String getArtifactSha256() { return artifactSha256; }
  public Long getArtifactLength() { return artifactLength; }
  public Long getDurationMs() { return durationMs; }
  public FetchOutcome getOutcome() { return outcome; }
  public Instant getFetchedAt() { return fetchedAt; }
  public String getErrorMessage() { return errorMessage; }
  public void assignCrate(UUID value) { crateId = Objects.requireNonNull(value); }
}
