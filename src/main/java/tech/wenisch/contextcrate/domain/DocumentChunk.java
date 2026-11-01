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

  /** {@code text} or {@code code}; null on chunks created before code-aware chunking. */
  @Column(length = 16)
  private String kind;

  @Column(length = 32)
  private String language;

  @Column(length = 512)
  private String symbol;

  @Column(name = "start_line")
  private Integer startLine;

  @Column(name = "end_line")
  private Integer endLine;

  @Column(name = "media_url", length = 2000)
  private String mediaUrl;

  public String getMediaUrl() {
    return mediaUrl;
  }

  public DocumentChunk media(String url) {
    this.mediaUrl = url;
    return this;
  }

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

  public DocumentChunk describe(
      String kind, String language, String symbol, Integer startLine, Integer endLine) {
    this.kind = kind;
    this.language = language;
    this.symbol = symbol == null || symbol.length() <= 512 ? symbol : symbol.substring(0, 512);
    this.startLine = startLine;
    this.endLine = endLine;
    return this;
  }

  @com.fasterxml.jackson.annotation.JsonIgnore
  public boolean isCode() {
    return "code".equals(kind);
  }

  /** The text as shown to people and models: code chunks come back inside a fenced block. */
  public String displayContent() {
    String text = isCode() ? "```" + (language == null ? "" : language) + "\n" + content + "\n```" : content;
    return mediaUrl == null ? text : text + "\n(Image: " + mediaUrl + ")";
  }

  public String getKind() {
    return kind;
  }

  public String getLanguage() {
    return language;
  }

  public String getSymbol() {
    return symbol;
  }

  public Integer getStartLine() {
    return startLine;
  }

  public Integer getEndLine() {
    return endLine;
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
