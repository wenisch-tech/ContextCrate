package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * LLM summary of one document section (hierarchical retrieval) and the exact position of that section:
 * document, heading path, first and last chunk ordinal and, for code, the line range. The id is the
 * section id that the summary vector carries in the index and that links it back to its chunks.
 */
@Entity
@Table(name = "section_summary")
public class SectionSummary {
  @Id private UUID id;
  @Column(name = "crate_id", nullable = false) private UUID crateId;
  @Column(name = "document_id", nullable = false) private UUID documentId;
  @Column(length = 1000) private String heading;
  @Column(name = "first_ordinal", nullable = false) private int firstOrdinal;
  @Column(name = "last_ordinal", nullable = false) private int lastOrdinal;
  @Column(name = "start_line") private Integer startLine;
  @Column(name = "end_line") private Integer endLine;
  @Column(nullable = false, length = 64) private String fingerprint;
  @Column(length = 500) private String model;
  @Column(nullable = false, columnDefinition = "text") private String summary;
  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected SectionSummary() {}

  public SectionSummary(UUID id, UUID crateId, UUID documentId, String heading, int firstOrdinal, int lastOrdinal,
      Integer startLine, Integer endLine, String fingerprint, String model, String summary) {
    this.id = id; this.crateId = crateId; this.documentId = documentId; this.fingerprint = fingerprint;
    this.model = model; this.summary = summary; this.createdAt = Instant.now();
    position(heading, firstOrdinal, lastOrdinal, startLine, endLine);
  }

  /** The section may move inside its document without its text changing. */
  public void position(String heading, int firstOrdinal, int lastOrdinal, Integer startLine, Integer endLine) {
    this.heading = heading == null || heading.length() <= 1000 ? heading : heading.substring(0, 1000);
    this.firstOrdinal = firstOrdinal; this.lastOrdinal = lastOrdinal; this.startLine = startLine; this.endLine = endLine;
  }

  public UUID getId() { return id; }
  public UUID getCrateId() { return crateId; }
  public UUID getDocumentId() { return documentId; }
  public String getHeading() { return heading; }
  public int getFirstOrdinal() { return firstOrdinal; }
  public int getLastOrdinal() { return lastOrdinal; }
  public Integer getStartLine() { return startLine; }
  public Integer getEndLine() { return endLine; }
  public String getFingerprint() { return fingerprint; }
  public String getModel() { return model; }
  public String getSummary() { return summary; }
  public Instant getCreatedAt() { return createdAt; }
}
