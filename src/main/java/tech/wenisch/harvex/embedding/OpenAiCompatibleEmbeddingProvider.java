package tech.wenisch.harvex.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;
import tech.wenisch.harvex.config.RuntimeProviderSettings;

@Component
public class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {
  private final RuntimeProviderSettings settings;
  private final ObjectMapper mapper;
  private final HttpClient client;
  private volatile int dimensions;

  public OpenAiCompatibleEmbeddingProvider(RuntimeProviderSettings settings, ObjectMapper mapper) {
    this.settings = settings;
    this.mapper = mapper;
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
  }
  @Override public ModelDescriptor descriptor() { var config=settings.effectiveEmbedding();return new ModelDescriptor("openai-compatible", config.openaiModel(), config.openaiModel(), dimensions == 0 ? config.openaiDimensions() : dimensions, true, "", ""); }
  @Override public boolean available() { var config=settings.effectiveEmbedding();return config.enabled() && config.openaiBaseUrl() != null && !config.openaiBaseUrl().isBlank() && config.openaiModel() != null && !config.openaiModel().isBlank(); }
  @Override public List<float[]> embedDocuments(List<String> texts) throws Exception { return embed(texts); }
  @Override public List<float[]> embedQueries(List<String> texts) throws Exception { return embed(texts); }

  private List<float[]> embed(List<String> texts) throws Exception {
    var config=settings.effectiveEmbedding();
    if (!available()) throw new IllegalStateException("OpenAI-compatible embedding endpoint and model must be configured");
    String url = config.openaiBaseUrl().replaceAll("/+$", "") + "/embeddings";
    var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/json");
    if (config.openaiApiKey() != null && !config.openaiApiKey().isBlank()) request.header("Authorization", "Bearer " + config.openaiApiKey());
    String payload = mapper.writeValueAsString(Map.of("model", config.openaiModel(), "input", texts, "encoding_format", "float"));
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
