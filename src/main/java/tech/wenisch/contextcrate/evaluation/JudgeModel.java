package tech.wenisch.contextcrate.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.metrics.CrateMetrics;

/**
 * OpenAI-compatible chat client for the judge model. The judge is configured per crate and
 * independently of the answer model; it is only ever called by the evaluation worker.
 */
@Component
public class JudgeModel implements JudgeClient {
  static final int MAX_OUTPUT_TOKENS = 1_500;
  private final EvaluationSettingsService settings;
  private final ObjectMapper mapper;
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
  private CrateMetrics metrics = CrateMetrics.noop();

  public JudgeModel(EvaluationSettingsService settings, ObjectMapper mapper) {
    this.settings = settings; this.mapper = mapper;
  }

  @Autowired(required = false)
  public void setMetrics(CrateMetrics metrics) { this.metrics = metrics; }

  @Override public boolean available(UUID crateId) { return settings.judge(crateId) != null; }

  @Override public String model(UUID crateId) {
    var config = settings.judge(crateId);
    return config == null ? null : config.model();
  }

  @Override
  public String ask(UUID crateId, String system, String user) throws Exception {
    var config = settings.judge(crateId);
    if (config == null) throw new IllegalStateException("No judge model is configured");
    long started = System.nanoTime();
    String outcome = "ok";
    try {
      var request = HttpRequest.newBuilder(URI.create(config.baseUrl().replaceAll("/+$", "") + "/chat/completions"))
          .timeout(Duration.ofSeconds(config.timeoutSeconds())).header("Content-Type", "application/json");
      if (config.apiKey() != null && !config.apiKey().isBlank()) request.header("Authorization", "Bearer " + config.apiKey());
      var body = Map.of("model", config.model(), "stream", false, "temperature", 0, "max_tokens", MAX_OUTPUT_TOKENS,
          "messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
      var response = client.send(request.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
          HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) throw new IllegalStateException("Judge model returned HTTP " + response.statusCode());
      var content = mapper.readTree(response.body()).path("choices").path(0).path("message").path("content");
      if (content.isMissingNode() || content.isNull()) throw new IllegalStateException("Judge model returned no completion content");
      return content.asText();
    } catch (Exception e) {
      outcome = "error";
      throw e;
    } finally {
      metrics.count("llm.requests", crateId, "purpose", "judge", "outcome", outcome);
      metrics.time("llm.duration", crateId, System.nanoTime() - started, "purpose", "judge");
    }
  }
}
