package tech.wenisch.harvex.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "extraction_result",
    uniqueConstraints =
        @UniqueConstraint(
            columnNames = {"rule_id", "chunk_id", "match_start", "match_end", "matched_value"}))
public class ExtractionResult {
  @Id private UUID id;

  @Column(name = "rule_id", nullable = false)
  private UUID ruleId;

  @Column(name = "run_id", nullable = false)
  private UUID runId;

  @Column(name = "document_id", nullable = false)
  private UUID documentId;

  @Column(name = "chunk_id", nullable = false)
  private UUID chunkId;

  @Column(name = "chunk_ordinal", nullable = false)
  private int chunkOrdinal;

  @Column(name = "matched_value", nullable = false, length = 4096)
  private String matchedValue;

  @Column(name = "match_start", nullable = false)
  private int matchStart;

  @Column(name = "match_end", nullable = false)
  private int matchEnd;

  @Column(name = "context_before", columnDefinition = "text")
  private String contextBefore;

  @Column(name = "context_after", columnDefinition = "text")
  private String contextAfter;

  @Column(name = "extracted_at", nullable = false)
  private Instant extractedAt;

  protected ExtractionResult() {}

  public ExtractionResult(
      UUID id,
      UUID ruleId,
      UUID runId,
      UUID documentId,
      UUID chunkId,
      int chunkOrdinal,
      String matchedValue,
      int matchStart,
      int matchEnd,
      String contextBefore,
      String contextAfter) {
    this.id = id;
    this.ruleId = ruleId;
    this.runId = runId;
    this.documentId = documentId;
    this.chunkId = chunkId;
    this.chunkOrdinal = chunkOrdinal;
    this.matchedValue = matchedValue;
    this.matchStart = matchStart;
    this.matchEnd = matchEnd;
    this.contextBefore = contextBefore;
    this.contextAfter = contextAfter;
    this.extractedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getRuleId() {
    return ruleId;
  }

  public UUID getRunId() {
    return runId;
  }

  public UUID getDocumentId() {
    return documentId;
  }

  public UUID getChunkId() {
    return chunkId;
  }

  public int getChunkOrdinal() {
    return chunkOrdinal;
  }

  public String getMatchedValue() {
    return matchedValue;
  }

  public int getMatchStart() {
    return matchStart;
  }

  public int getMatchEnd() {
    return matchEnd;
  }

  public String getContextBefore() {
    return contextBefore;
  }

  public String getContextAfter() {
    return contextAfter;
  }

  public Instant getExtractedAt() {
    return extractedAt;
  }
}