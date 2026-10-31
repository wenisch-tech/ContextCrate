package tech.wenisch.contextcrate.embedding;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.config.RuntimeProviderSettings;
@Component @Primary
public class ConfigurableEmbeddingProvider implements EmbeddingProvider {
  private final RuntimeProviderSettings settings; private final LocalOnnxEmbeddingProvider local; private final OpenAiCompatibleEmbeddingProvider remote;
  public ConfigurableEmbeddingProvider(RuntimeProviderSettings settings,LocalOnnxEmbeddingProvider local,OpenAiCompatibleEmbeddingProvider remote){this.settings=settings;this.local=local;this.remote=remote;}
  private EmbeddingProvider delegate(){return "openai-compatible".equals(settings.effectiveEmbedding().provider())?remote:local;}
  @Override public ModelDescriptor descriptor(){return delegate().descriptor();}
  @Override public boolean available(){return settings.effectiveEmbedding().enabled()&&delegate().available();}
  @Override public List<float[]> embedDocuments(List<String> texts)throws Exception{return timed("document",texts,()->delegate().embedDocuments(texts));}
  @Override public List<float[]> embedQueries(List<String> texts)throws Exception{return timed("query",texts,()->delegate().embedQueries(texts));}
  private tech.wenisch.contextcrate.metrics.CrateMetrics metrics=tech.wenisch.contextcrate.metrics.CrateMetrics.noop();
  @org.springframework.beans.factory.annotation.Autowired(required=false) public void setMetrics(tech.wenisch.contextcrate.metrics.CrateMetrics metrics){this.metrics=metrics;}
  private interface Call{List<float[]> run()throws Exception;}
  private List<float[]> timed(String op,List<String> texts,Call call)throws Exception{
    var crate=tech.wenisch.contextcrate.config.CrateContext.current();String provider=String.valueOf(settings.effectiveEmbedding().provider());long started=System.nanoTime();String outcome="ok";
    try{return call.run();}catch(Exception e){outcome=e instanceof EmbeddingInputTooLargeException?"input_too_large":"error";throw e;}
    finally{metrics.count("embedding.requests",crate,"provider",provider,"op",op,"outcome",outcome);metrics.time("embedding.duration",crate,System.nanoTime()-started,"provider",provider,"op",op);metrics.summary("embedding.batch_size",crate,texts.size(),"provider",provider,"op",op);}
  }
}
