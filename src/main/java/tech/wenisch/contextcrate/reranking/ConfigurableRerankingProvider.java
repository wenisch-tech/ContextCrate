package tech.wenisch.contextcrate.reranking;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.config.RuntimeProviderSettings;
@Component @Primary
public class ConfigurableRerankingProvider implements RerankingProvider {
  private final RuntimeProviderSettings settings; private final LocalOnnxRerankingProvider local; private final CohereCompatibleRerankingProvider remote;
  public ConfigurableRerankingProvider(RuntimeProviderSettings settings,LocalOnnxRerankingProvider local,CohereCompatibleRerankingProvider remote){this.settings=settings;this.local=local;this.remote=remote;}
  private RerankingProvider delegate(){return "cohere-compatible".equals(settings.effectiveReranking().provider())?remote:local;}
  @Override public boolean available(){return settings.effectiveReranking().enabled()&&delegate().available();}
  @Override public int candidateLimit(){return settings.effectiveReranking().candidateLimit();}
  @Override public List<Float> rerank(String query,List<String> documents)throws Exception{return delegate().rerank(query,documents);}
  @Override public void downloadModel()throws Exception{if(!"local".equals(settings.effectiveReranking().provider()))throw new IllegalStateException("Select the local reranking provider first");local.downloadModel();}
}
