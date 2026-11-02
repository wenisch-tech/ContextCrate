package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Generated title (and optionally summary) of one document, used to build contextual chunk headers.
 * Only successful generations are stored; the fingerprint decides whether a stored row is still valid.
 */
@Entity
@Table(name = "document_context")
public class DocumentContext {
  @Id private UUID id;
  @Column(name = "crate_id", nullable = false) private UUID crateId;
  @Column(name = "document_id", nullable = false, unique = true) private UUID documentId;
  @Column(name = "content_hash", nullable = false, length = 64) private String contentHash;
  @Column(nullable = false, length = 64) private String fingerprint;
  @Column(length = 500) private String model;
  @Column(nullable = false, length = 500) private String title;
  @Column(columnDefinition = "text") private String summary;
  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected DocumentContext() {}

  public DocumentContext(UUID id, UUID crateId, UUID documentId, String contentHash,
      String fingerprint, String model, String title, String summary) {
    this.id = id; this.crateId = crateId; this.documentId = documentId; this.contentHash = contentHash;
    this.fingerprint = fingerprint; this.model = model; this.title = title; this.summary = summary;
    this.createdAt = Instant.now();
  }

  public UUID getId() { return id; }
  public UUID getCrateId() { return crateId; }
  public UUID getDocumentId() { return documentId; }
  public String getContentHash() { return contentHash; }
  public String getFingerprint() { return fingerprint; }
  public String getModel() { return model; }
  public String getTitle() { return title; }
  public String getSummary() { return summary; }
  public Instant getCreatedAt() { return createdAt; }
}
