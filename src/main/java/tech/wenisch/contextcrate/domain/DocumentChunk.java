package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(
    name = "document_chunk",
    uniqueConstraints = @UniqueConstraint(columnNames = {"document_id", "ordinal"}))
public class DocumentChunk {
  @Id private UUID id;

  @Column(name = "crate_id", nullable = false)
  private UUID crateId;

  @Column(name = "document_id", nullable = false)
  private UUID documentId;

  @Column(nullable = false)
  private int ordinal;

  @Column(length = 1000)
  private String heading;

  @Column(nullable = false, columnDefinition = "text")
  private String content;

  @Column(name = "content_hash", nullable = false, length = 64)
  private String contentHash;

  @Column(name = "character_count", nullable = false)
  private int characterCount;

  @Column(name = "token_estimate", nullable = false)
  private int tokenEstimate;

  protected DocumentChunk() {}

  public DocumentChunk(
      UUID id, UUID documentId, int ordinal, String heading, String content, String hash) {
    this.id = id;
    this.crateId = CrateIds.LEGACY;
    this.documentId = documentId;
    this.ordinal = ordinal;
    this.heading = heading;
    this.content = content;
    this.contentHash = hash;
    this.characterCount = content.length();
    this.tokenEstimate = Math.max(1, content.length() / 4);
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

  public UUID getDocumentId() {
    return documentId;
  }

  public int getOrdinal() {
    return ordinal;
  }

  public String getHeading() {
    return heading;
  }

  public String getContent() {
    return content;
  }

  public String getContentHash() {
    return contentHash;
  }

  public int getCharacterCount() {
    return characterCount;
  }

  public int getTokenEstimate() {
    return tokenEstimate;
  }
}
