package tech.wenisch.contextcrate.reranking;

import ai.djl.huggingface.tokenizers.*;
import ai.onnxruntime.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.LongBuffer;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.config.RuntimeProviderSettings;

/** Generic Hugging Face ONNX sequence-classification cross encoder. */
@Component
public class LocalOnnxRerankingProvider implements RerankingProvider {
  private static final int MAX_SEQUENCE_TOKENS=512; private final RuntimeProviderSettings settings;
  private volatile HuggingFaceTokenizer tokenizer; private volatile OrtEnvironment environment; private volatile OrtSession session; private volatile Path loadedRoot;
  public LocalOnnxRerankingProvider(RuntimeProviderSettings settings){this.settings=settings;}
  @Override public boolean available(){try{var c=settings.effectiveReranking();if(!c.enabled()||c.localModelId()==null||c.localModelId().isBlank())return false;initialize();return true;}catch(Exception e){return false;}}
  @Override public int candidateLimit(){return settings.effectiveReranking().candidateLimit();}
  @Override public List<Float> rerank(String query,List<String> documents)throws Exception{initialize();List<Float> scores=new ArrayList<>();for(String document:documents)scores.add(score(query,document));return scores;}
  @Override public void downloadModel() throws Exception { initialize(); }
  private synchronized void initialize()throws Exception{var c=settings.effectiveReranking();Path root=modelRoot(c);if(session!=null&&root.equals(loadedRoot))return;if(session!=null){session.close();session=null;}ensureBundle(root,c);Path model=root.resolve("onnx/model_quantized.onnx");if(!Files.isRegularFile(model))model=root.resolve("onnx/model.onnx");if(!Files.isRegularFile(model)||!Files.isRegularFile(root.resolve("tokenizer.json")))throw new IllegalStateException("Local reranking bundle must contain tokenizer.json and onnx/model_quantized.onnx (or onnx/model.onnx): "+root);tokenizer=HuggingFaceTokenizer.newInstance(root.resolve("tokenizer.json"));environment=OrtEnvironment.getEnvironment();session=environment.createSession(model.toString(),new OrtSession.SessionOptions());loadedRoot=root;}
  private Path modelRoot(RuntimeProviderSettings.Reranking c){return c.localModelPath()!=null?c.localModelPath().toAbsolutePath().normalize():c.localCachePath().resolve(c.localModelId().replace('/','_')).resolve(c.localRevision()==null?"main":c.localRevision()).toAbsolutePath().normalize();}
  private void ensureBundle(Path root,RuntimeProviderSettings.Reranking c)throws Exception{if(Files.isRegularFile(root.resolve("tokenizer.json"))&&(Files.isRegularFile(root.resolve("onnx/model_quantized.onnx"))||Files.isRegularFile(root.resolve("onnx/model.onnx"))))return;if(c.localModelPath()!=null||c.localDownloadUrl()==null||c.localDownloadUrl().isBlank())return;Files.createDirectories(root.resolve("onnx"));HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(c.localDownloadTimeoutSeconds())).build();download(client,c,"tokenizer.json",root.resolve("tokenizer.json"));download(client,c,"config.json",root.resolve("config.json"));download(client,c,"onnx/model_quantized.onnx",root.resolve("onnx/model_quantized.onnx"));}
  private static void download(HttpClient client,RuntimeProviderSettings.Reranking c,String file,Path target)throws Exception{Path temporary=target.resolveSibling(target.getFileName()+".part");var response=client.send(HttpRequest.newBuilder(URI.create(c.localDownloadUrl().replaceAll("/+$","")+"/"+file)).timeout(Duration.ofSeconds(c.localDownloadTimeoutSeconds())).GET().build(),HttpResponse.BodyHandlers.ofFile(temporary));if(response.statusCode()/100!=2){Files.deleteIfExists(temporary);throw new IOException("Could not download local reranking model asset "+file+" (HTTP "+response.statusCode()+")");}Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
  private float score(String query,String document)throws Exception{Encoding e=tokenizer.encode(query,document==null?"":document);long[] ids=limit(e.getIds()),mask=limit(e.getAttentionMask()),types=limit(e.getTypeIds());long[] shape={1,ids.length};Map<String,OnnxTensor> inputs=new HashMap<>();try{for(String name:session.getInputNames()){long[] values=name.contains("attention")?mask:name.contains("token_type")?types:ids;inputs.put(name,OnnxTensor.createTensor(environment,LongBuffer.wrap(values),shape));}try(OrtSession.Result result=session.run(inputs)){OnnxValue value=result.get("logits").orElse(result.iterator().next().getValue());Object output=value.getValue();if(output instanceof float[][] values)return values[0][values[0].length-1];if(output instanceof float[] values)return values[values.length-1];throw new IllegalStateException("Local reranking model must return float logits");}}finally{for(OnnxTensor value:inputs.values())value.close();}}
  private static long[] limit(long[] values){return values.length<=MAX_SEQUENCE_TOKENS?values:Arrays.copyOf(values,MAX_SEQUENCE_TOKENS);}
  @Override public synchronized void close()throws Exception{if(session!=null)session.close();session=null;loadedRoot=null;}
}
