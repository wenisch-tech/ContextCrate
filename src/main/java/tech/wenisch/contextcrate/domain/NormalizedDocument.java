package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "normalized_document",
    uniqueConstraints = @UniqueConstraint(columnNames = {"run_id", "canonical_url"}))
public class NormalizedDocument {
  @Id private UUID id;

  @Column(name = "crate_id", nullable = false)
  private UUID crateId;

  @Column(name = "run_id", nullable = false)
  private UUID runId;

  @Column(name = "fetch_id", nullable = false)
  private UUID fetchId;

  @Column(name = "canonical_url", nullable = false, length = 4096)
  private String canonicalUrl;

  @Column(length = 1000)
  private String title;

  private String language;

  @Column(length = 4000)
  private String description;

  @Column(length = 500)
  private String author;

  @Column(nullable = false, columnDefinition = "text")
  private String body;

  @Column(name = "content_hash", nullable = false, length = 64)
  private String contentHash;

  @Column(name = "metadata_json", nullable = false, columnDefinition = "text")
  private String metadataJson;

  @Column(nullable = false)
  private boolean indexed;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected NormalizedDocument() {}

  public NormalizedDocument(
      UUID id,
      UUID runId,
      UUID fetchId,
      String canonicalUrl,
      String title,
      String language,
      String description,
      String author,
      String body,
      String hash,
      String metadataJson) {
    this.id = id;
    this.crateId = CrateIds.LEGACY;
    this.runId = runId;
    this.fetchId = fetchId;
    this.canonicalUrl = canonicalUrl;
    this.title = title;
    this.language = language;
    this.description = description;
    this.author = author;
    this.body = body;
    this.contentHash = hash;
    this.metadataJson = metadataJson;
    this.indexed = false;
    this.createdAt = Instant.now();
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

  public UUID getFetchId() {
    return fetchId;
  }

  public String getCanonicalUrl() {
    return canonicalUrl;
  }

  public String getTitle() {
    return title;
  }

  public String getLanguage() {
    return language;
  }

  public String getDescription() {
    return description;
  }

  public String getAuthor() {
    return author;
  }

  public String getBody() {
    return body;
  }

  public String getContentHash() {
    return contentHash;
  }

  public String getMetadataJson() {
    return metadataJson;
  }

  public boolean isIndexed() {
    return indexed;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void indexed() {
    indexed = true;
  }
}
