package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;
import java.util.UUID;

/**
 * Per-crate RAG evaluation settings. Only administrators may read or change them. The judge model is
 * independent of the answer model: either an administrator-managed shared model of kind JUDGE or an
 * own OpenAI-compatible endpoint.
 */
@Entity
@Table(name = "crate_evaluation_settings")
public class EvaluationSettings {
  @Id @Column(name = "crate_id") private UUID crateId;
  private boolean enabled;
  @Column(name = "sampling_rate", nullable = false) private double samplingRate = 1.0;
  @Column(name = "top_k", nullable = false) private int topK = 5;
  @Column(name = "pool_size", nullable = false) private int poolSize = 20;
  @Column(name = "context_enabled", nullable = false) private boolean contextEnabled = true;
  @Column(name = "faithfulness_enabled", nullable = false) private boolean faithfulnessEnabled = true;
  @Column(name = "answer_relevance_enabled", nullable = false) private boolean answerRelevanceEnabled = true;
  @Column(name = "completeness_enabled", nullable = false) private boolean completenessEnabled = true;
  /** When true questions, answers, delivered chunks and judge reasoning are kept per case for the retention period. */
  @Column(name = "log_questions", nullable = false) private boolean logQuestions;
  @Column(name = "retention_days", nullable = false) private int retentionDays = 30;
  @Column(name = "judge_shared_model_id") private UUID judgeSharedModelId;
  @Column(name = "judge_base_url", length = 2000) private String judgeBaseUrl;
  @Column(name = "judge_model", length = 500) private String judgeModel;
  @Column(name = "judge_api_key", length = 2000) private String judgeApiKey;
  @Column(name = "judge_timeout_seconds", nullable = false) private int judgeTimeoutSeconds = 120;

  protected EvaluationSettings() {}
  public EvaluationSettings(UUID crateId) { this.crateId = crateId; }

  public UUID getCrateId() { return crateId; }
  public boolean isEnabled() { return enabled; }
  public double getSamplingRate() { return samplingRate; }
  public int getTopK() { return topK; }
  public int getPoolSize() { return poolSize; }
  public boolean isContextEnabled() { return contextEnabled; }
  public boolean isFaithfulnessEnabled() { return faithfulnessEnabled; }
  public boolean isAnswerRelevanceEnabled() { return answerRelevanceEnabled; }
  public boolean isCompletenessEnabled() { return completenessEnabled; }
  public boolean isLogQuestions() { return logQuestions; }
  public int getRetentionDays() { return retentionDays; }
  public UUID getJudgeSharedModelId() { return judgeSharedModelId; }
  public String getJudgeBaseUrl() { return judgeBaseUrl; }
  public String getJudgeModel() { return judgeModel; }
  /** Never exposed through the API or the UI. */
  public String getJudgeApiKey() { return judgeApiKey; }
  public int getJudgeTimeoutSeconds() { return judgeTimeoutSeconds; }
  public boolean hasJudgeApiKey() { return judgeApiKey != null && !judgeApiKey.isBlank(); }

  public void update(boolean enabled, double samplingRate, int topK, int poolSize, boolean context,
      boolean faithfulness, boolean answerRelevance, boolean completeness, boolean logQuestions, int retentionDays) {
    this.enabled = enabled; this.samplingRate = samplingRate; this.topK = topK; this.poolSize = poolSize;
    this.contextEnabled = context; this.faithfulnessEnabled = faithfulness;
    this.answerRelevanceEnabled = answerRelevance; this.completenessEnabled = completeness;
    this.logQuestions = logQuestions; this.retentionDays = retentionDays;
  }

  /** A blank API key keeps the stored key. A shared model replaces the own endpoint. */
  public void updateJudge(UUID sharedModelId, String baseUrl, String model, String apiKey, int timeoutSeconds) {
    this.judgeSharedModelId = sharedModelId;
    this.judgeBaseUrl = sharedModelId == null ? baseUrl : null;
    this.judgeModel = sharedModelId == null ? model : null;
    if (sharedModelId != null) this.judgeApiKey = null;
    else if (apiKey != null && !apiKey.isBlank()) this.judgeApiKey = apiKey;
    this.judgeTimeoutSeconds = timeoutSeconds;
  }
}
