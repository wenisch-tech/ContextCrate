package tech.wenisch.contextcrate.domain;

import static tech.wenisch.contextcrate.domain.PipelineTypes.FetchOutcome;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fetch_record")
public class FetchRecord {
  @Id private UUID id;

  @Column(name = "crate_id", nullable = false)
  private UUID crateId;

  @Column(name = "run_id", nullable = false)
  private UUID runId;

  @Column(name = "frontier_entry_id", nullable = false)
  private UUID frontierEntryId;

  @Column(name = "requested_url", nullable = false, length = 4096)
  private String requestedUrl;

  @Column(name = "final_url", length = 4096)
  private String finalUrl;

  @Column(name = "status_code")
  private Integer statusCode;

  @Column(name = "content_type")
  private String contentType;

  private String charset;

  @Column(name = "artifact_key", length = 1024)
  private String artifactKey;

  @Column(name = "artifact_sha256", length = 64)
  private String artifactSha256;

  @Column(name = "artifact_length")
  private Long artifactLength;

  @Column(name = "duration_ms")
  private Long durationMs;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private FetchOutcome outcome;

  @Column(name = "fetched_at", nullable = false)
  private Instant fetchedAt;

  @Column(name = "error_message", columnDefinition = "text")
  private String errorMessage;

  protected FetchRecord() {}

  public FetchRecord(UUID id, UUID runId, UUID frontierId, String requestedUrl) {
    this.id = id;
    this.crateId = CrateIds.LEGACY;
    this.runId = runId;
    this.frontierEntryId = frontierId;
    this.requestedUrl = requestedUrl;
    this.outcome = FetchOutcome.FAILED;
    this.fetchedAt = Instant.now();
  }

  public void success(
      String finalUrl,
      int status,
      String contentType,
      String charset,
      String key,
      String sha,
      long length,
      long duration) {
    this.finalUrl = finalUrl;
    this.statusCode = status;
    this.contentType = contentType;
    this.charset = charset;
    this.artifactKey = key;
    this.artifactSha256 = sha;
    this.artifactLength = length;
    this.durationMs = duration;
    this.outcome = status >= 200 && status < 400 ? FetchOutcome.SUCCEEDED : FetchOutcome.HTTP_ERROR;
  }

  public void failure(FetchOutcome outcome, String message) {
    this.outcome = outcome;
    this.errorMessage = message;
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

  public UUID getFrontierEntryId() {
    return frontierEntryId;
  }

  public String getRequestedUrl() {
    return requestedUrl;
  }

  public String getFinalUrl() {
    return finalUrl;
  }

  public Integer getStatusCode() {
    return statusCode;
  }

  public String getContentType() {
    return contentType;
  }

  public String getCharset() {
    return charset;
  }

  public String getArtifactKey() {
    return artifactKey;
  }

  public String getArtifactSha256() {
    return artifactSha256;
  }

  public Long getArtifactLength() {
    return artifactLength;
  }

  public Long getDurationMs() {
    return durationMs;
  }

  public FetchOutcome getOutcome() {
    return outcome;
  }

  public Instant getFetchedAt() {
    return fetchedAt;
  }

  public String getErrorMessage() {
    return errorMessage;
  }
}
