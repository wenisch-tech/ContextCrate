package tech.wenisch.contextcrate.config;

import java.nio.file.Path;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.domain.ProviderSettings;
import tech.wenisch.contextcrate.repository.ProviderSettingsRepository;

/** Database overrides take precedence over environment-bound configuration. */
@Service
public class RuntimeProviderSettings {
  private final ProviderSettingsRepository repository; private final ContextCrateProperties properties;
  public RuntimeProviderSettings(ProviderSettingsRepository repository,ContextCrateProperties properties){this.repository=repository;this.properties=properties;}
  @Transactional(readOnly = true) public ProviderSettings current(){return current(CrateContext.current());}
  @Transactional(readOnly = true) public ProviderSettings current(java.util.UUID crateId){return repository.findById(crateId).orElseThrow(()->new IllegalStateException("Provider settings are not initialized for crate "+crateId));}
  @Transactional public void update(ProviderForm f){update(CrateContext.current(),f);}
  @Transactional public void update(java.util.UUID crateId,ProviderForm f){if(!"local".equals(f.embeddingProvider())&& !"openai-compatible".equals(f.embeddingProvider()))throw new IllegalArgumentException("embedding provider must be local or openai-compatible");if(f.embeddingDimensions()<1)throw new IllegalArgumentException("embedding dimensions must be positive");if(f.embeddingMaxInputCharacters()<256)throw new IllegalArgumentException("maximum embedding input characters must be at least 256");current(crateId).update(f.embeddingsEnabled(),f.embeddingProvider(),blank(f.embeddingModelId()),blank(f.embeddingRevision()),blank(f.embeddingDownloadUrl()),blank(f.embeddingCachePath()),blank(f.embeddingModelPath()),blank(f.embeddingBaseUrl()),blank(f.embeddingRemoteModel()),f.embeddingApiKey(),f.embeddingDimensions(),f.embeddingMaxInputCharacters(),f.answeringEnabled(),blank(f.answeringBaseUrl()),blank(f.answeringModel()),f.answeringApiKey());}
  public Embedding effectiveEmbedding(){var s=current();var e=properties.embeddings();var l=e.local();var o=e.openaiCompatible();return new Embedding(s.getEmbeddingsEnabled()==null?e.enabled():s.getEmbeddingsEnabled(),choose(s.getEmbeddingsProvider(),e.provider()),choose(s.getEmbeddingLocalModelId(),l.modelId()),choose(s.getEmbeddingLocalRevision(),l.revision()),choose(s.getEmbeddingLocalDownloadUrl(),l.downloadUrl()),path(s.getEmbeddingLocalCachePath(),l.cachePath()),pathNullable(s.getEmbeddingLocalModelPath(),l.modelPath()),choose(s.getEmbeddingOpenaiBaseUrl(),o.baseUrl()),choose(s.getEmbeddingOpenaiModel(),o.model()),choose(s.getEmbeddingOpenaiApiKey(),o.apiKey()),s.getEmbeddingOpenaiDimensions()==null?o.dimensions():s.getEmbeddingOpenaiDimensions(),s.getEmbeddingOpenaiMaxInputCharacters()==null?o.maxInputCharacters():s.getEmbeddingOpenaiMaxInputCharacters());}
  public Embedding effectiveEmbedding(java.util.UUID crateId){try(var ignored=CrateContext.use(crateId)){return effectiveEmbedding();}}
  public Answering effectiveAnswer(){var s=current();var a=properties.answering();var o=a.openaiCompatible();return new Answering(s.getAnsweringEnabled()==null?a.enabled():s.getAnsweringEnabled(),choose(s.getAnsweringBaseUrl(),o.baseUrl()),choose(s.getAnsweringModel(),o.model()),choose(s.getAnsweringApiKey(),o.apiKey()),o.headers(),o.timeoutSeconds(),a.temperature(),a.maxOutputTokens());}
  public Answering effectiveAnswer(java.util.UUID crateId){try(var ignored=CrateContext.use(crateId)){return effectiveAnswer();}}
  private static String choose(String value,String fallback){return value==null||value.isBlank()?fallback:value;}private static Path path(String value,Path fallback){return value==null||value.isBlank()?fallback:Path.of(value);}private static Path pathNullable(String value,Path fallback){return value==null||value.isBlank()?fallback:Path.of(value);}private static String blank(String value){return value==null||value.isBlank()?null:value.trim();}
  public record Embedding(boolean enabled,String provider,String localModelId,String localRevision,String localDownloadUrl,Path localCachePath,Path localModelPath,String openaiBaseUrl,String openaiModel,String openaiApiKey,int openaiDimensions,int openaiMaxInputCharacters){}
  public record Answering(boolean enabled,String baseUrl,String model,String apiKey,String headers,int timeoutSeconds,double temperature,int maxOutputTokens){}
  public record ProviderForm(boolean embeddingsEnabled,String embeddingProvider,String embeddingModelId,String embeddingRevision,String embeddingDownloadUrl,String embeddingCachePath,String embeddingModelPath,String embeddingBaseUrl,String embeddingRemoteModel,String embeddingApiKey,int embeddingDimensions,int embeddingMaxInputCharacters,boolean answeringEnabled,String answeringBaseUrl,String answeringModel,String answeringApiKey){}
}
