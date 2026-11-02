package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** A question with the documents that should be found and, optionally, the answer that should be given. */
@Entity
@Table(name = "evaluation_golden_item")
public class EvaluationGoldenItem {
  @Id private UUID id;
  @Column(name = "crate_id", nullable = false) private UUID crateId;
  @Column(nullable = false, columnDefinition = "text") private String question;
  @Column(name = "reference_answer", columnDefinition = "text") private String referenceAnswer;
  /** JSON array of source URIs (or URI prefixes) of the documents that are relevant for the question. */
  @Column(name = "relevant_uris", columnDefinition = "text") private String relevantUris;
  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected EvaluationGoldenItem() {}
  public EvaluationGoldenItem(UUID id, UUID crateId, String question, String referenceAnswer, String relevantUris) {
    this.id = id; this.crateId = crateId; this.question = question; this.referenceAnswer = referenceAnswer;
    this.relevantUris = relevantUris; this.createdAt = Instant.now();
  }
  public UUID getId() { return id; }
  public UUID getCrateId() { return crateId; }
  public String getQuestion() { return question; }
  public String getReferenceAnswer() { return referenceAnswer; }
  public String getRelevantUris() { return relevantUris; }
  public Instant getCreatedAt() { return createdAt; }
}
