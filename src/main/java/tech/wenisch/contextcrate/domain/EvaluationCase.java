package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * One evaluated answer. The text columns ({@code question}, {@code answer}, {@code snapshotJson},
 * {@code detailsJson}) exist only while the case waits for evaluation, or permanently (until the
 * retention period ends) when per-question logging is on. Otherwise they are blanked and only the
 * numeric scores remain.
 */
@Entity
@Table(name = "evaluation_case")
public class EvaluationCase {
  public enum Source { LIVE, GOLDEN }
  public enum Status { PENDING, DONE, FAILED }

  @Id private UUID id;
  @Column(name = "crate_id", nullable = false) private UUID crateId;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private Source source;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private Status status;
  @Column(name = "golden_item_id") private UUID goldenItemId;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "evaluated_at") private Instant evaluatedAt;
  @Column(columnDefinition = "text") private String question;
  @Column(columnDefinition = "text") private String answer;
  @Column(name = "snapshot_json", columnDefinition = "text") private String snapshotJson;
  @Column(name = "details_json", columnDefinition = "text") private String detailsJson;
  private Integer k;
  @Column(name = "precision_at_k") private Double precisionAtK;
  @Column(name = "recall_at_k") private Double recallAtK;
  private Double mrr;
  @Column(name = "average_precision") private Double averagePrecision;
  private Double ndcg;
  private Double faithfulness;
  @Column(name = "statements_total") private Integer statementsTotal;
  @Column(name = "statements_supported") private Integer statementsSupported;
  @Column(name = "answer_relevance") private Double answerRelevance;
  private Double completeness;
  @Column(name = "reference_recall") private Double referenceRecall;
  @Column(name = "judge_model", length = 500) private String judgeModel;
  @Column(columnDefinition = "text") private String error;
  /** Neutral rewrite of the question; kept after {@link #scrub()}. */
  @Column(columnDefinition = "text") private String topic;
  /** JSON array of up to 5 category tags; kept after {@link #scrub()}. */
  @Column(columnDefinition = "text") private String tags;

  protected EvaluationCase() {}

  public EvaluationCase(UUID id, UUID crateId, Source source, UUID goldenItemId, String question, String answer, String snapshotJson) {
    this.id = id; this.crateId = crateId; this.source = source; this.goldenItemId = goldenItemId;
    this.question = question; this.answer = answer; this.snapshotJson = snapshotJson;
    this.status = Status.PENDING; this.createdAt = Instant.now();
  }

  public UUID getId() { return id; }
  public UUID getCrateId() { return crateId; }
  public Source getSource() { return source; }
  public Status getStatus() { return status; }
  public UUID getGoldenItemId() { return goldenItemId; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getEvaluatedAt() { return evaluatedAt; }
  public String getQuestion() { return question; }
  public String getAnswer() { return answer; }
  public String getSnapshotJson() { return snapshotJson; }
  public String getDetailsJson() { return detailsJson; }
  public Integer getK() { return k; }
  public Double getPrecisionAtK() { return precisionAtK; }
  public Double getRecallAtK() { return recallAtK; }
  public Double getMrr() { return mrr; }
  public Double getAveragePrecision() { return averagePrecision; }
  public Double getNdcg() { return ndcg; }
  public Double getFaithfulness() { return faithfulness; }
  public Integer getStatementsTotal() { return statementsTotal; }
  public Integer getStatementsSupported() { return statementsSupported; }
  public Double getAnswerRelevance() { return answerRelevance; }
  public Double getCompleteness() { return completeness; }
  public Double getReferenceRecall() { return referenceRecall; }
  public String getJudgeModel() { return judgeModel; }
  public String getError() { return error; }
  public String getTopic() { return topic; }
  public String getTags() { return tags; }

  public void topic(String topic, String tagsJson) { this.topic = topic; this.tags = tagsJson; }

  /** Replaces the question and answer of a golden case once the worker has produced the answer. */
  public void answered(String answer, String snapshotJson) { this.answer = answer; this.snapshotJson = snapshotJson; }

  public void context(int k, Double precision, Double recall, Double mrr, Double averagePrecision, Double ndcg) {
    this.k = k; this.precisionAtK = precision; this.recallAtK = recall; this.mrr = mrr;
    this.averagePrecision = averagePrecision; this.ndcg = ndcg;
  }
  public void faithfulness(Double score, int total, int supported) { this.faithfulness = score; this.statementsTotal = total; this.statementsSupported = supported; }
  public void answerRelevance(Double score) { this.answerRelevance = score; }
  public void completeness(Double score) { this.completeness = score; }
  public void referenceRecall(Double score) { this.referenceRecall = score; }

  public void done(String judgeModel, String detailsJson, String error) {
    this.status = Status.DONE; this.evaluatedAt = Instant.now(); this.judgeModel = judgeModel;
    this.detailsJson = detailsJson; this.error = error;
  }
  public void failed(String error) { this.status = Status.FAILED; this.evaluatedAt = Instant.now(); this.error = error; }

  /** Removes everything that came from users or documents; the numeric scores, topic and tags stay. */
  public void scrub() { this.question = null; this.answer = null; this.snapshotJson = null; this.detailsJson = null; }
  public boolean hasText() { return question != null || answer != null || snapshotJson != null || detailsJson != null; }
}
