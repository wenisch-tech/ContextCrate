package tech.wenisch.harvex.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tech.wenisch.harvex.config.HarvexProperties;

@Component
@ConditionalOnExpression("'${harvex.embeddings.enabled:true}' == 'true' and '${harvex.embeddings.provider:local}' == 'openai-compatible'")
public class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {
  private final HarvexProperties.Embeddings.OpenAiCompatible config;
  private final ObjectMapper mapper;
  private final HttpClient client;
  private volatile int dimensions;

  public OpenAiCompatibleEmbeddingProvider(HarvexProperties properties, ObjectMapper mapper) {
    config = properties.embeddings().openaiCompatible();
    this.mapper = mapper;
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(config.timeoutSeconds())).build();
  }
  @Override public ModelDescriptor descriptor() { return new ModelDescriptor("openai-compatible", config.model(), config.model(), dimensions == 0 ? config.dimensions() : dimensions, true, "", ""); }
  @Override public boolean available() { return config.baseUrl() != null && !config.baseUrl().isBlank() && config.model() != null && !config.model().isBlank(); }
  @Override public List<float[]> embedDocuments(List<String> texts) throws Exception { return embed(texts); }
  @Override public List<float[]> embedQueries(List<String> texts) throws Exception { return embed(texts); }

  private List<float[]> embed(List<String> texts) throws Exception {
    if (!available()) throw new IllegalStateException("OpenAI-compatible embedding endpoint and model must be configured");
    String url = config.baseUrl().replaceAll("/+$", "") + "/embeddings";
    var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(config.timeoutSeconds()))
        .header("Content-Type", "application/json");
    if (config.apiKey() != null && !config.apiKey().isBlank()) request.header("Authorization", "Bearer " + config.apiKey());
    if (config.headers() != null) for (String header : config.headers().split("\\n")) {
      int colon = header.indexOf(':'); if (colon > 0) request.header(header.substring(0, colon).trim(), header.substring(colon + 1).trim());
    }
    String payload = mapper.writeValueAsString(Map.of("model", config.model(), "input", texts, "encoding_format", "float"));
    var response = client.send(request.POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) throw new IllegalStateException("Embedding endpoint returned HTTP " + response.statusCode());
    JsonNode data = mapper.readTree(response.body()).path("data");
    List<float[]> vectors = new ArrayList<>();
    for (JsonNode item : data) { float[] vector = new float[item.path("embedding").size()]; for (int i=0;i<vector.length;i++) vector[i]=(float)item.path("embedding").get(i).asDouble(); vectors.add(normalize(vector)); }
    if (vectors.size() != texts.size() || vectors.isEmpty()) throw new IllegalStateException("Embedding endpoint returned an invalid response");
    dimensions = vectors.getFirst().length;
    if (vectors.stream().anyMatch(v -> v.length != dimensions)) throw new IllegalStateException("Embedding endpoint returned inconsistent dimensions");
    return vectors;
  }
  static float[] normalize(float[] v) { double sum=0; for(float x:v) sum+=x*x; double n=Math.sqrt(sum); if(n==0) throw new IllegalStateException("Embedding vector is zero"); for(int i=0;i<v.length;i++) v[i]/=(float)n; return v; }
}
