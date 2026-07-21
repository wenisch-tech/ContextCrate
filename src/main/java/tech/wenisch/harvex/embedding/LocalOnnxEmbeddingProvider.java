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
import org.springframework.stereotype.Component;
import tech.wenisch.harvex.config.RuntimeProviderSettings;

@Component
public class LocalOnnxEmbeddingProvider implements EmbeddingProvider {
  private final RuntimeProviderSettings settings;
  private volatile HuggingFaceTokenizer tokenizer;
  private volatile OrtEnvironment environment;
  private volatile OrtSession session;
  private volatile Path loadedRoot;

  public LocalOnnxEmbeddingProvider(RuntimeProviderSettings settings) { this.settings = settings; }
  @Override public ModelDescriptor descriptor() { var config=settings.effectiveEmbedding();return new ModelDescriptor("local", config.localModelId(), config.localRevision(), 384, true, "query: ", "passage: "); }
  @Override public boolean available() { try { initialize(); return true; } catch (Exception e) { return false; } }
  @Override public List<float[]> embedDocuments(List<String> texts) throws Exception { return embed(texts, descriptor().documentPrefix()); }
  @Override public List<float[]> embedQueries(List<String> texts) throws Exception { return embed(texts, descriptor().queryPrefix()); }
  /** Downloads/validates the selected local bundle without embedding content. */
  public void downloadModel() throws Exception { initialize(); }

  private synchronized void initialize() throws Exception {
    var config=settings.effectiveEmbedding(); Path root = modelRoot(config);
    if (session != null && root.equals(loadedRoot)) return;
    if (session != null) { session.close(); session = null; }
    ensureBundle(root);
    Path model = root.resolve("onnx/model_quantized.onnx");
    if (!Files.isRegularFile(model)) model = root.resolve("onnx/model.onnx");
    if (!Files.isRegularFile(model) || !Files.isRegularFile(root.resolve("tokenizer.json")))
      throw new IllegalStateException("Local embedding bundle must contain tokenizer.json and onnx/model_quantized.onnx (or onnx/model.onnx): " + root);
    tokenizer = HuggingFaceTokenizer.newInstance(root.resolve("tokenizer.json"));
    environment = OrtEnvironment.getEnvironment();
    session = environment.createSession(model.toString(), new OrtSession.SessionOptions());
    loadedRoot = root;
  }

  private Path modelRoot(RuntimeProviderSettings.Embedding config) { return config.localModelPath() != null ? config.localModelPath().toAbsolutePath().normalize() : config.localCachePath().resolve(config.localModelId().replace('/', '_')).resolve(config.localRevision()).toAbsolutePath().normalize(); }
  private void ensureBundle(Path root) throws Exception {
    var config=settings.effectiveEmbedding();
    if (Files.isRegularFile(root.resolve("tokenizer.json")) && (Files.isRegularFile(root.resolve("onnx/model_quantized.onnx")) || Files.isRegularFile(root.resolve("onnx/model.onnx")))) return;
    if (config.localModelPath() != null) return;
    Files.createDirectories(root.resolve("onnx"));
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(120)).build();
    download(client, "tokenizer.json", root.resolve("tokenizer.json"));
    download(client, "config.json", root.resolve("config.json"));
    download(client, "onnx/model_quantized.onnx", root.resolve("onnx/model_quantized.onnx"));
  }
  private void download(HttpClient client, String source, Path target) throws Exception {
    var config=settings.effectiveEmbedding();
    Path temporary = target.resolveSibling(target.getFileName() + ".part");
    var response = client.send(HttpRequest.newBuilder(URI.create(config.localDownloadUrl().replaceAll("/+$", "") + "/" + source)).timeout(Duration.ofSeconds(120)).GET().build(), HttpResponse.BodyHandlers.ofFile(temporary));
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
  @Override public synchronized void close() throws Exception { if(session != null) session.close(); if(environment != null) environment.close(); session=null;loadedRoot=null; }
}
