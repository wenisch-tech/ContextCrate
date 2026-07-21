package tech.wenisch.harvex.embedding;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import tech.wenisch.harvex.config.RuntimeProviderSettings;
@Component @Primary
public class ConfigurableEmbeddingProvider implements EmbeddingProvider {
  private final RuntimeProviderSettings settings; private final LocalOnnxEmbeddingProvider local; private final OpenAiCompatibleEmbeddingProvider remote;
  public ConfigurableEmbeddingProvider(RuntimeProviderSettings settings,LocalOnnxEmbeddingProvider local,OpenAiCompatibleEmbeddingProvider remote){this.settings=settings;this.local=local;this.remote=remote;}
  private EmbeddingProvider delegate(){return "openai-compatible".equals(settings.effectiveEmbedding().provider())?remote:local;}
  @Override public ModelDescriptor descriptor(){return delegate().descriptor();}
  @Override public boolean available(){return settings.effectiveEmbedding().enabled()&&delegate().available();}
  @Override public List<float[]> embedDocuments(List<String> texts)throws Exception{return delegate().embedDocuments(texts);}
  @Override public List<float[]> embedQueries(List<String> texts)throws Exception{return delegate().embedQueries(texts);}
}
