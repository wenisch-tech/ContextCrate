package tech.wenisch.harvex.embedding;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.tokenizers.Encoding;
import ai.onnxruntime.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.LongBuffer;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tech.wenisch.harvex.config.HarvexProperties;

@Component
@ConditionalOnExpression("'${harvex.embeddings.enabled:true}' == 'true' and '${harvex.embeddings.provider:local}' == 'local'")
public class LocalOnnxEmbeddingProvider implements EmbeddingProvider {
  private final HarvexProperties.Embeddings.Local config;
  private volatile HuggingFaceTokenizer tokenizer;
  private volatile OrtEnvironment environment;
  private volatile OrtSession session;

  public LocalOnnxEmbeddingProvider(HarvexProperties properties) { config = properties.embeddings().local(); }
  @Override public ModelDescriptor descriptor() { return new ModelDescriptor("local", config.modelId(), config.revision(), 384, true, "query: ", "passage: "); }
  @Override public boolean available() { try { initialize(); return true; } catch (Exception e) { return false; } }
  @Override public List<float[]> embedDocuments(List<String> texts) throws Exception { return embed(texts, descriptor().documentPrefix()); }
  @Override public List<float[]> embedQueries(List<String> texts) throws Exception { return embed(texts, descriptor().queryPrefix()); }

  private synchronized void initialize() throws Exception {
    if (session != null) return;
    Path root = modelRoot();
    ensureBundle(root);
    Path model = root.resolve("onnx/model_quantized.onnx");
    if (!Files.isRegularFile(model)) model = root.resolve("onnx/model.onnx");
    if (!Files.isRegularFile(model) || !Files.isRegularFile(root.resolve("tokenizer.json")))
      throw new IllegalStateException("Local embedding bundle must contain tokenizer.json and onnx/model_quantized.onnx (or onnx/model.onnx): " + root);
    tokenizer = HuggingFaceTokenizer.newInstance(root.resolve("tokenizer.json"));
    environment = OrtEnvironment.getEnvironment();
    session = environment.createSession(model.toString(), new OrtSession.SessionOptions());
  }

  private Path modelRoot() { return config.modelPath() != null ? config.modelPath().toAbsolutePath().normalize() : config.cachePath().resolve(config.modelId().replace('/', '_')).resolve(config.revision()).toAbsolutePath().normalize(); }
  private void ensureBundle(Path root) throws Exception {
    if (Files.isRegularFile(root.resolve("tokenizer.json")) && (Files.isRegularFile(root.resolve("onnx/model_quantized.onnx")) || Files.isRegularFile(root.resolve("onnx/model.onnx")))) return;
    if (config.modelPath() != null) return;
    Files.createDirectories(root.resolve("onnx"));
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(config.downloadTimeoutSeconds())).build();
    download(client, "tokenizer.json", root.resolve("tokenizer.json"));
    download(client, "config.json", root.resolve("config.json"));
    download(client, "onnx/model_quantized.onnx", root.resolve("onnx/model_quantized.onnx"));
  }
  private void download(HttpClient client, String source, Path target) throws Exception {
    Path temporary = target.resolveSibling(target.getFileName() + ".part");
    var response = client.send(HttpRequest.newBuilder(URI.create(config.downloadUrl().replaceAll("/+$", "") + "/" + source)).timeout(Duration.ofSeconds(config.downloadTimeoutSeconds())).GET().build(), HttpResponse.BodyHandlers.ofFile(temporary));
    if (response.statusCode() / 100 != 2) { Files.deleteIfExists(temporary); throw new IOException("Could not download local embedding model asset " + source + " (HTTP " + response.statusCode() + ")"); }
    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }
  private List<float[]> embed(List<String> texts, String prefix) throws Exception {
    initialize(); List<float[]> vectors = new ArrayList<>();
    for (String text : texts) vectors.add(embedOne(prefix + (text == null ? "" : text)));
    return vectors;
  }
  private float[] embedOne(String text) throws Exception {
    Encoding encoding = tokenizer.encode(text);
    long[] ids = encoding.getIds(); long[] mask = encoding.getAttentionMask();
    long[] types = encoding.getTypeIds(); long[] shape = {1, ids.length};
    try (OnnxTensor inputIds = OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape);
         OnnxTensor attention = OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), shape);
         OnnxTensor tokenTypes = OnnxTensor.createTensor(environment, LongBuffer.wrap(types), shape);
         OrtSession.Result result = session.run(Map.of("input_ids", inputIds, "attention_mask", attention, "token_type_ids", tokenTypes))) {
      float[][][] states = (float[][][]) result.get("last_hidden_state").orElseThrow().getValue();
      float[] vector = new float[states[0][0].length]; long count=0;
      for(int i=0;i<states[0].length;i++) if(mask[i] == 1) { for(int j=0;j<vector.length;j++) vector[j]+=states[0][i][j]; count++; }
      for(int j=0;j<vector.length;j++) vector[j]/=count;
      return OpenAiCompatibleEmbeddingProvider.normalize(vector);
    }
  }
  @Override public synchronized void close() throws Exception { if(session != null) session.close(); if(environment != null) environment.close(); }
}
