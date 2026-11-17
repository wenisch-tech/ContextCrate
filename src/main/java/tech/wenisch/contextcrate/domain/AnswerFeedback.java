package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** One answer that can be rated: question, answer, the two ratings and the state of its use for index enrichment. */
@Entity
@Table(name = "answer_feedback")
public class AnswerFeedback {
  public enum Channel { UI, OPENAI, MCP }
  public enum Enrichment { NONE, CANDIDATE, APPROVED, INDEXED, REJECTED }

  @Id private UUID id;
  @Column(name = "crate_id", nullable = false) private UUID crateId;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private Channel channel;
  @Column(length = 255) private String actor;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "expires_at", nullable = false) private Instant expiresAt;
  @Column(name = "token_hash", nullable = false, length = 64) private String tokenHash;
  @Column(nullable = false, columnDefinition = "text") private String question;
  @Column(nullable = false, columnDefinition = "text") private String answer;
  private Short relevance;
  private Short quality;
  @Column(columnDefinition = "text") private String comment;
  @Column(name = "rated_at") private Instant ratedAt;
  @Enumerated(EnumType.STRING) @Column(name = "enrichment_status", nullable = false, length = 12) private Enrichment enrichmentStatus = Enrichment.NONE;
  @Column(name = "enriched_document_id") private UUID enrichedDocumentId;

  protected AnswerFeedback() {}

  public AnswerFeedback(UUID id, UUID crateId, Channel channel, String actor, Instant expiresAt, String tokenHash, String question, String answer) {
    this.id = id; this.crateId = crateId; this.channel = channel; this.actor = actor;
    this.createdAt = Instant.now(); this.expiresAt = expiresAt; this.tokenHash = tokenHash;
    this.question = question; this.answer = answer;
  }

  public UUID getId() { return id; }
  public UUID getCrateId() { return crateId; }
  public Channel getChannel() { return channel; }
  public String getActor() { return actor; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getExpiresAt() { return expiresAt; }
  public String getQuestion() { return question; }
  public String getAnswer() { return answer; }
  public Short getRelevance() { return relevance; }
  public Short getQuality() { return quality; }
  public String getComment() { return comment; }
  public Instant getRatedAt() { return ratedAt; }
  public Enrichment getEnrichmentStatus() { return enrichmentStatus; }
  public UUID getEnrichedDocumentId() { return enrichedDocumentId; }
  public boolean isExpired(Instant now) { return !expiresAt.isAfter(now); }

  /** Stores the ratings; a dimension that is {@code null} keeps its previous value. */
  public void rate(Integer relevance, Integer quality, String comment) {
    if (relevance != null) this.relevance = relevance.shortValue();
    if (quality != null) this.quality = quality.shortValue();
    if (comment != null) this.comment = comment.isBlank() ? null : comment.strip();
    this.ratedAt = Instant.now();
  }
  public void enrichment(Enrichment value) { this.enrichmentStatus = value; }
  public void indexedAs(UUID documentId) { this.enrichedDocumentId = documentId; this.enrichmentStatus = Enrichment.INDEXED; }
}
