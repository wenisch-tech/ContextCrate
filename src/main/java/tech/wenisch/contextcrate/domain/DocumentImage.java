package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** An image found while parsing a document, with the outcome of filtering and description. */
@Entity
@Table(name = "document_image")
public class DocumentImage {
  public enum Status { REFERENCED, QUEUED, DESCRIBED, SKIPPED, FAILED }

  @Id private UUID id;
  @Column(name = "crate_id", nullable = false) private UUID crateId;
  @Column(name = "document_id", nullable = false) private UUID documentId;
  @Column(name = "run_id", nullable = false) private UUID runId;
  @Column(nullable = false, length = 2000) private String url;
  @Column(name = "view_url", length = 2000) private String viewUrl;
  @Column(length = 1000) private String alt;
  @Column(length = 1000) private String caption;
  @Column(length = 1000) private String context;
  @Column(length = 1000) private String heading;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Status status;
  @Column(name = "skip_reason", length = 120) private String skipReason;
  @Column(name = "artifact_key", length = 500) private String artifactKey;
  @Column(name = "content_hash", length = 64) private String contentHash;
  @Column(columnDefinition = "text") private String description;
  @Column(length = 500) private String model;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "described_at") private Instant describedAt;

  protected DocumentImage() {}

  public DocumentImage(UUID crateId, UUID documentId, UUID runId, String url, String viewUrl,
      String alt, String caption, String context, String heading) {
    this.id = UUID.randomUUID(); this.crateId = crateId; this.documentId = documentId;
    this.runId = runId; this.url = cut(url, 2000); this.viewUrl = cut(viewUrl, 2000);
    this.alt = cut(alt, 1000); this.caption = cut(caption, 1000); this.context = cut(context, 1000);
    this.heading = cut(heading, 1000); this.status = Status.REFERENCED; this.createdAt = Instant.now();
  }

  private static String cut(String v, int n) { return v == null || v.length() <= n ? v : v.substring(0, n); }

  public void skip(String reason) { this.status = Status.SKIPPED; this.skipReason = cut(reason, 120); }
  public void queue(String artifactKey) { this.status = Status.QUEUED; this.artifactKey = artifactKey; }
  public void fail(String reason) { this.status = Status.FAILED; this.skipReason = cut(reason, 120); }
  public void stored(String artifactKey, String hash) { this.artifactKey = artifactKey; this.contentHash = hash; }
  public void described(String description, String model) {
    this.status = Status.DESCRIBED; this.description = description; this.model = model;
    this.describedAt = Instant.now(); this.skipReason = null;
  }

  public UUID getId() { return id; }
  public UUID getCrateId() { return crateId; }
  public UUID getDocumentId() { return documentId; }
  public UUID getRunId() { return runId; }
  public String getUrl() { return url; }
  public String getViewUrl() { return viewUrl; }
  public String getAlt() { return alt; }
  public String getCaption() { return caption; }
  public String getContext() { return context; }
  public String getHeading() { return heading; }
  public Status getStatus() { return status; }
  public String getSkipReason() { return skipReason; }
  public String getArtifactKey() { return artifactKey; }
  public String getContentHash() { return contentHash; }
  public String getDescription() { return description; }
  public String getModel() { return model; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getDescribedAt() { return describedAt; }
}
