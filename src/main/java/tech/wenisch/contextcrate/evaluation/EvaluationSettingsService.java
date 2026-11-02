package tech.wenisch.contextcrate.evaluation;

import java.net.URI;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.domain.EvaluationSettings;
import tech.wenisch.contextcrate.domain.SharedModel;
import tech.wenisch.contextcrate.repository.EvaluationSettingsRepository;
import tech.wenisch.contextcrate.repository.SharedModelRepository;

/** Reads and validates the per-crate evaluation settings. Callers must already have checked for an administrator. */
@Service
public class EvaluationSettingsService {
  public static final int MIN_TOP_K = 1, MAX_TOP_K = 20, MIN_POOL = 5, MAX_POOL = 100, MAX_RETENTION_DAYS = 365;
  public static final double MIN_SAMPLING = 0.01;

  /** Resolved judge endpoint; never serialised. */
  public record JudgeConfig(String baseUrl, String model, String apiKey, int timeoutSeconds) {}

  /** What an administrator may see. The judge API key is never part of it. */
  public record View(boolean enabled, double samplingRate, int topK, int poolSize, boolean context, boolean faithfulness,
      boolean answerRelevance, boolean completeness, boolean logQuestions, int retentionDays, UUID judgeSharedModelId,
      String judgeSharedModelName, String judgeBaseUrl, String judgeModel, boolean judgeApiKeySet, int judgeTimeoutSeconds,
      boolean judgeConfigured) {}

  /** Every field is optional; a missing field keeps the stored value. */
  public record Request(Boolean enabled, Double samplingRate, Integer topK, Integer poolSize, Boolean context,
      Boolean faithfulness, Boolean answerRelevance, Boolean completeness, Boolean logQuestions, Integer retentionDays,
      UUID judgeSharedModelId, String judgeBaseUrl, String judgeModel, String judgeApiKey, Integer judgeTimeoutSeconds) {}

  private final EvaluationSettingsRepository settings;
  private final SharedModelRepository sharedModels;

  private tech.wenisch.contextcrate.config.RuntimeProviderSettings policy;

  public EvaluationSettingsService(EvaluationSettingsRepository settings, SharedModelRepository sharedModels) {
    this.settings = settings; this.sharedModels = sharedModels;
  }

  /** Gives access to the administrator's model policy ("custom endpoints" switch). */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setPolicy(tech.wenisch.contextcrate.config.RuntimeProviderSettings policy) { this.policy = policy; }

  /** The stored settings or the defaults (disabled); the defaults are not stored until saved. */
  @Transactional(readOnly = true)
  public EvaluationSettings current(UUID crateId) {
    return settings.findById(crateId).orElseGet(() -> new EvaluationSettings(crateId));
  }

  @Transactional
  public EvaluationSettings update(UUID crateId, boolean enabled, double samplingRate, int topK, int poolSize,
      boolean context, boolean faithfulness, boolean answerRelevance, boolean completeness, boolean logQuestions,
      int retentionDays) {
    if (Double.isNaN(samplingRate) || samplingRate < MIN_SAMPLING || samplingRate > 1.0)
      throw new IllegalArgumentException("sampling rate must be between 0.01 and 1");
    if (topK < MIN_TOP_K || topK > MAX_TOP_K) throw new IllegalArgumentException("K must be between 1 and 20");
    if (poolSize < MIN_POOL || poolSize > MAX_POOL || poolSize < topK)
      throw new IllegalArgumentException("pool size must be between 5 and 100 and not smaller than K");
    if (retentionDays < 1 || retentionDays > MAX_RETENTION_DAYS)
      throw new IllegalArgumentException("retention must be between 1 and 365 days");
    var value = settings.findById(crateId).orElseGet(() -> new EvaluationSettings(crateId));
    value.update(enabled, samplingRate, topK, poolSize, context, faithfulness, answerRelevance, completeness, logQuestions, retentionDays);
    return settings.save(value);
  }

  @Transactional
  public EvaluationSettings updateJudge(UUID crateId, UUID sharedModelId, String baseUrl, String model, String apiKey, int timeoutSeconds) {
    if (timeoutSeconds < 5 || timeoutSeconds > 600) throw new IllegalArgumentException("judge timeout must be between 5 and 600 seconds");
    if (sharedModelId != null) {
      var shared = sharedModels.findById(sharedModelId).orElseThrow(() -> new IllegalArgumentException("unknown judge model"));
      if (shared.getKind() != SharedModel.Kind.JUDGE) throw new IllegalArgumentException("selected model is not a judge model");
    } else if (baseUrl != null && !baseUrl.isBlank()) {
      try {
        URI uri = URI.create(baseUrl.trim());
        if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) throw new IllegalArgumentException();
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("judge endpoint must be an http(s) URL");
      }
      if (model == null || model.isBlank()) throw new IllegalArgumentException("judge model identifier is required");
    }
    var value = settings.findById(crateId).orElseGet(() -> new EvaluationSettings(crateId));
    // Same rule as for the answer model: when the administrator disables custom endpoints, owners may
    // only choose shared models. Keeping the endpoint that is already stored stays possible.
    if (sharedModelId == null && policy != null && !policy.allowCustomEndpoints() && (blank(baseUrl) != null || blank(model) != null || (apiKey != null && !apiKey.isBlank()))) {
      boolean unchanged = java.util.Objects.equals(blank(baseUrl), value.getJudgeBaseUrl()) && java.util.Objects.equals(blank(model), value.getJudgeModel())
          && (apiKey == null || apiKey.isBlank());
      if (!unchanged) throw new IllegalArgumentException("Custom endpoints are disabled by the administrator; choose a shared model instead");
    }
    value.updateJudge(sharedModelId, blank(baseUrl), blank(model), apiKey, timeoutSeconds);
    return settings.save(value);
  }

  @Transactional(readOnly = true)
  public View view(UUID crateId) {
    var v = current(crateId);
    return new View(v.isEnabled(), v.getSamplingRate(), v.getTopK(), v.getPoolSize(), v.isContextEnabled(), v.isFaithfulnessEnabled(),
        v.isAnswerRelevanceEnabled(), v.isCompletenessEnabled(), v.isLogQuestions(), v.getRetentionDays(), v.getJudgeSharedModelId(),
        sharedJudgeName(v.getJudgeSharedModelId()), v.getJudgeBaseUrl(), v.getJudgeModel(), v.hasJudgeApiKey(), v.getJudgeTimeoutSeconds(),
        judge(crateId) != null);
  }

  /** Validates and stores a (partial) update; a judge shared model replaces an own endpoint and the other way round. */
  @Transactional
  public View apply(UUID crateId, Request r) {
    var c = current(crateId);
    update(crateId, pick(r.enabled(), c.isEnabled()), pick(r.samplingRate(), c.getSamplingRate()), pick(r.topK(), c.getTopK()),
        pick(r.poolSize(), c.getPoolSize()), pick(r.context(), c.isContextEnabled()), pick(r.faithfulness(), c.isFaithfulnessEnabled()),
        pick(r.answerRelevance(), c.isAnswerRelevanceEnabled()), pick(r.completeness(), c.isCompletenessEnabled()),
        pick(r.logQuestions(), c.isLogQuestions()), pick(r.retentionDays(), c.getRetentionDays()));
    if (r.judgeSharedModelId() != null || r.judgeBaseUrl() != null || r.judgeModel() != null || r.judgeApiKey() != null || r.judgeTimeoutSeconds() != null) {
      boolean own = r.judgeSharedModelId() == null && (r.judgeBaseUrl() != null || r.judgeModel() != null || c.getJudgeSharedModelId() == null);
      UUID shared = r.judgeSharedModelId() != null ? r.judgeSharedModelId() : own ? null : c.getJudgeSharedModelId();
      updateJudge(crateId, shared, r.judgeBaseUrl() != null ? r.judgeBaseUrl() : c.getJudgeBaseUrl(),
          r.judgeModel() != null ? r.judgeModel() : c.getJudgeModel(), r.judgeApiKey(), pick(r.judgeTimeoutSeconds(), c.getJudgeTimeoutSeconds()));
    }
    return view(crateId);
  }

  private static <T> T pick(T value, T fallback) { return value == null ? fallback : value; }

  /** The judge endpoint of the crate, or {@code null} when none is configured. */
  @Transactional(readOnly = true)
  public JudgeConfig judge(UUID crateId) {
    var value = settings.findById(crateId).orElse(null);
    if (value == null) return null;
    if (value.getJudgeSharedModelId() != null) {
      return sharedModels.findById(value.getJudgeSharedModelId()).filter(m -> m.getKind() == SharedModel.Kind.JUDGE)
          .map(m -> new JudgeConfig(m.getBaseUrl(), m.getModel(), m.getApiKey(),
              m.getTimeoutSeconds() == null ? value.getJudgeTimeoutSeconds() : m.getTimeoutSeconds()))
          .orElse(null);
    }
    if (value.getJudgeBaseUrl() == null || value.getJudgeBaseUrl().isBlank() || value.getJudgeModel() == null || value.getJudgeModel().isBlank()) return null;
    return new JudgeConfig(value.getJudgeBaseUrl(), value.getJudgeModel(), value.getJudgeApiKey(), value.getJudgeTimeoutSeconds());
  }

  @Transactional(readOnly = true)
  public boolean judgeAvailable(UUID crateId) { return judge(crateId) != null; }

  /** Administrator-visible name of the selected shared judge model, or {@code null}. */
  @Transactional(readOnly = true)
  public String sharedJudgeName(UUID id) { return id == null ? null : sharedModels.findById(id).map(SharedModel::getName).orElse(null); }

  private static String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
