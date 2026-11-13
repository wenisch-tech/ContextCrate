package tech.wenisch.contextcrate.reranking;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.config.RuntimeProviderSettings;
@Component @Primary
public class ConfigurableRerankingProvider implements RerankingProvider {
  private final RuntimeProviderSettings settings; private final LocalOnnxRerankingProvider local; private final CohereCompatibleRerankingProvider remote;
  public ConfigurableRerankingProvider(RuntimeProviderSettings settings,LocalOnnxRerankingProvider local,CohereCompatibleRerankingProvider remote){this.settings=settings;this.local=local;this.remote=remote;}
  private tech.wenisch.contextcrate.answer.LlmRerankService llm; private tech.wenisch.contextcrate.answer.RagSettingsService rag;
  @org.springframework.beans.factory.annotation.Autowired(required=false) public void setLlm(tech.wenisch.contextcrate.answer.LlmRerankService llm,tech.wenisch.contextcrate.answer.RagSettingsService rag){this.llm=llm;this.rag=rag;}
  private tech.wenisch.contextcrate.domain.RagSettings llmSettings(){if(llm==null||rag==null)return null;var crate=tech.wenisch.contextcrate.config.CrateContext.current();if(crate==null)return null;var r=rag.current(crate);return r.isLlmRerankEnabled()?r:null;}
  private boolean llmActive(){return llmSettings()!=null&&llm.available();}
  private RerankingProvider delegate(){return "cohere-compatible".equals(settings.effectiveReranking().provider())?remote:local;}
  @Override public boolean available(){return llmActive()||(settings.effectiveReranking().enabled()&&delegate().available());}
  @Override public int resultLimit(int requested){var r=llmActive()?llmSettings():null;return r==null?requested:Math.min(requested,r.getLlmRerankTopN());}
  @Override public int candidateLimit(){return settings.effectiveReranking().candidateLimit();}
  private tech.wenisch.contextcrate.metrics.CrateMetrics metrics=tech.wenisch.contextcrate.metrics.CrateMetrics.noop();
  @org.springframework.beans.factory.annotation.Autowired(required=false) public void setMetrics(tech.wenisch.contextcrate.metrics.CrateMetrics metrics){this.metrics=metrics;}
  @Override public List<Float> rerank(String query,List<String> documents)throws Exception{
    var crate=tech.wenisch.contextcrate.config.CrateContext.current();String provider=String.valueOf(settings.effectiveReranking().provider());long started=System.nanoTime();String outcome="ok";
    try{if(llmActive()){provider="llm";return llm.score(crate,query,documents);}return delegate().rerank(query,documents);}catch(Exception e){outcome="error";throw e;}
    finally{metrics.count("rerank.requests",crate,"provider",provider,"outcome",outcome);metrics.time("rerank.duration",crate,System.nanoTime()-started,"provider",provider);metrics.summary("rerank.candidates",crate,documents.size(),"provider",provider);}
  }
  @Override public void downloadModel()throws Exception{if(!"local".equals(settings.effectiveReranking().provider()))throw new IllegalStateException("Select the local reranking provider first");local.downloadModel();}
}
