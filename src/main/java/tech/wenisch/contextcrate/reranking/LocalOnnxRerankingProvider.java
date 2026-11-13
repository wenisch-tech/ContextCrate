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
  private static final int MAX_SEQUENCE_TOKENS=512,BATCH_SIZE=16; private final RuntimeProviderSettings settings;
  private volatile HuggingFaceTokenizer tokenizer; private volatile OrtEnvironment environment; private volatile OrtSession session; private volatile Path loadedRoot;
  public LocalOnnxRerankingProvider(RuntimeProviderSettings settings){this.settings=settings;}
  @Override public boolean available(){try{var c=settings.effectiveReranking();if(!c.enabled()||c.localModelId()==null||c.localModelId().isBlank())return false;initialize();return true;}catch(Exception e){return false;}}
  @Override public int candidateLimit(){return settings.effectiveReranking().candidateLimit();}
  @Override public List<Float> rerank(String query,List<String> documents)throws Exception{initialize();return scoreBatch(query,documents);}
  @Override public void downloadModel() throws Exception { initialize(); }
  private synchronized void initialize()throws Exception{var c=settings.effectiveReranking();Path root=modelRoot(c);if(session!=null&&root.equals(loadedRoot))return;if(session!=null){session.close();session=null;}ensureBundle(root,c);Path model=root.resolve("onnx/model_quantized.onnx");if(!Files.isRegularFile(model))model=root.resolve("onnx/model.onnx");if(!Files.isRegularFile(model)||!Files.isRegularFile(root.resolve("tokenizer.json")))throw new IllegalStateException("Local reranking bundle must contain tokenizer.json and onnx/model_quantized.onnx (or onnx/model.onnx): "+root);tokenizer=HuggingFaceTokenizer.newInstance(root.resolve("tokenizer.json"));environment=OrtEnvironment.getEnvironment();session=environment.createSession(model.toString(),new OrtSession.SessionOptions());loadedRoot=root;}
  private Path modelRoot(RuntimeProviderSettings.Reranking c){return c.localModelPath()!=null?c.localModelPath().toAbsolutePath().normalize():c.localCachePath().resolve(c.localModelId().replace('/','_')).resolve(c.localRevision()==null?"main":c.localRevision()).toAbsolutePath().normalize();}
  private void ensureBundle(Path root,RuntimeProviderSettings.Reranking c)throws Exception{if(Files.isRegularFile(root.resolve("tokenizer.json"))&&(Files.isRegularFile(root.resolve("onnx/model_quantized.onnx"))||Files.isRegularFile(root.resolve("onnx/model.onnx"))))return;if(c.localModelPath()!=null||c.localDownloadUrl()==null||c.localDownloadUrl().isBlank())return;Files.createDirectories(root.resolve("onnx"));HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(c.localDownloadTimeoutSeconds())).build();download(client,c,"tokenizer.json",root.resolve("tokenizer.json"));download(client,c,"config.json",root.resolve("config.json"));download(client,c,"onnx/model_quantized.onnx",root.resolve("onnx/model_quantized.onnx"));}
  private static void download(HttpClient client,RuntimeProviderSettings.Reranking c,String file,Path target)throws Exception{Path temporary=target.resolveSibling(target.getFileName()+".part");var response=client.send(HttpRequest.newBuilder(URI.create(c.localDownloadUrl().replaceAll("/+$","")+"/"+file)).timeout(Duration.ofSeconds(c.localDownloadTimeoutSeconds())).GET().build(),HttpResponse.BodyHandlers.ofFile(temporary));if(response.statusCode()/100!=2){Files.deleteIfExists(temporary);throw new IOException("Could not download local reranking model asset "+file+" (HTTP "+response.statusCode()+")");}Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
  /** Scores the pairs (query, document) in batches: one ONNX run per batch, shorter pairs padded and masked out. */
  private List<Float> scoreBatch(String query,List<String> documents)throws Exception{
    List<Float> scores=new ArrayList<>();
    for(int from=0;from<documents.size();from+=BATCH_SIZE){
      var batch=documents.subList(from,Math.min(documents.size(),from+BATCH_SIZE));int n=batch.size(),width=1;
      long[][] ids=new long[n][],mask=new long[n][],types=new long[n][];
      for(int i=0;i<n;i++){Encoding e=tokenizer.encode(query,batch.get(i)==null?"":batch.get(i));ids[i]=limit(e.getIds());mask[i]=limit(e.getAttentionMask());types[i]=limit(e.getTypeIds());width=Math.max(width,ids[i].length);}
      long[] shape={n,width};Map<String,OnnxTensor> inputs=new HashMap<>();
      try{
        for(String name:session.getInputNames()){long[][] source=name.contains("attention")?mask:name.contains("token_type")?types:ids;long[] flat=new long[n*width];for(int i=0;i<n;i++)System.arraycopy(source[i],0,flat,i*width,source[i].length);inputs.put(name,OnnxTensor.createTensor(environment,LongBuffer.wrap(flat),shape));}
        try(OrtSession.Result result=session.run(inputs)){
          Object output=result.get("logits").orElse(result.iterator().next().getValue()).getValue();
          if(output instanceof float[][] rows&&rows.length==n){for(float[] row:rows)scores.add(row[row.length-1]);}
          else if(output instanceof float[] values&&n==1)scores.add(values[values.length-1]);
          else throw new IllegalStateException("Local reranking model must return float logits");
        }
      }finally{for(OnnxTensor value:inputs.values())value.close();}
    }
    return scores;
  }
  private static long[] limit(long[] values){return values.length<=MAX_SEQUENCE_TOKENS?values:Arrays.copyOf(values,MAX_SEQUENCE_TOKENS);}
  @Override public synchronized void close()throws Exception{if(session!=null)session.close();session=null;loadedRoot=null;}
}
