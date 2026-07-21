package tech.wenisch.harvex.config;

import java.nio.file.Path;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.harvex.domain.ProviderSettings;
import tech.wenisch.harvex.repository.ProviderSettingsRepository;

/** Database overrides take precedence over environment-bound configuration. */
@Service
public class RuntimeProviderSettings {
  private final ProviderSettingsRepository repository; private final HarvexProperties properties;
  public RuntimeProviderSettings(ProviderSettingsRepository repository,HarvexProperties properties){this.repository=repository;this.properties=properties;}
  @Transactional(readOnly = true) public ProviderSettings current(){return repository.findById(1).orElseThrow(()->new IllegalStateException("Provider settings are not initialized"));}
  @Transactional public void update(ProviderForm f){if(!"local".equals(f.embeddingProvider())&& !"openai-compatible".equals(f.embeddingProvider()))throw new IllegalArgumentException("embedding provider must be local or openai-compatible");if(f.embeddingDimensions()<1)throw new IllegalArgumentException("embedding dimensions must be positive");current().update(f.embeddingsEnabled(),f.embeddingProvider(),blank(f.embeddingModelId()),blank(f.embeddingRevision()),blank(f.embeddingDownloadUrl()),blank(f.embeddingCachePath()),blank(f.embeddingModelPath()),blank(f.embeddingBaseUrl()),blank(f.embeddingRemoteModel()),f.embeddingApiKey(),f.embeddingDimensions(),f.answeringEnabled(),blank(f.answeringBaseUrl()),blank(f.answeringModel()),f.answeringApiKey());}
  public Embedding effectiveEmbedding(){var s=current();var e=properties.embeddings();var l=e.local();var o=e.openaiCompatible();return new Embedding(s.getEmbeddingsEnabled()==null?e.enabled():s.getEmbeddingsEnabled(),choose(s.getEmbeddingsProvider(),e.provider()),choose(s.getEmbeddingLocalModelId(),l.modelId()),choose(s.getEmbeddingLocalRevision(),l.revision()),choose(s.getEmbeddingLocalDownloadUrl(),l.downloadUrl()),path(s.getEmbeddingLocalCachePath(),l.cachePath()),pathNullable(s.getEmbeddingLocalModelPath(),l.modelPath()),choose(s.getEmbeddingOpenaiBaseUrl(),o.baseUrl()),choose(s.getEmbeddingOpenaiModel(),o.model()),choose(s.getEmbeddingOpenaiApiKey(),o.apiKey()),s.getEmbeddingOpenaiDimensions()==null?o.dimensions():s.getEmbeddingOpenaiDimensions());}
  public Answering effectiveAnswer(){var s=current();var a=properties.answering();var o=a.openaiCompatible();return new Answering(s.getAnsweringEnabled()==null?a.enabled():s.getAnsweringEnabled(),choose(s.getAnsweringBaseUrl(),o.baseUrl()),choose(s.getAnsweringModel(),o.model()),choose(s.getAnsweringApiKey(),o.apiKey()),o.headers(),o.timeoutSeconds(),a.temperature(),a.maxOutputTokens());}
  private static String choose(String value,String fallback){return value==null||value.isBlank()?fallback:value;}private static Path path(String value,Path fallback){return value==null||value.isBlank()?fallback:Path.of(value);}private static Path pathNullable(String value,Path fallback){return value==null||value.isBlank()?fallback:Path.of(value);}private static String blank(String value){return value==null||value.isBlank()?null:value.trim();}
  public record Embedding(boolean enabled,String provider,String localModelId,String localRevision,String localDownloadUrl,Path localCachePath,Path localModelPath,String openaiBaseUrl,String openaiModel,String openaiApiKey,int openaiDimensions){}
  public record Answering(boolean enabled,String baseUrl,String model,String apiKey,String headers,int timeoutSeconds,double temperature,int maxOutputTokens){}
  public record ProviderForm(boolean embeddingsEnabled,String embeddingProvider,String embeddingModelId,String embeddingRevision,String embeddingDownloadUrl,String embeddingCachePath,String embeddingModelPath,String embeddingBaseUrl,String embeddingRemoteModel,String embeddingApiKey,int embeddingDimensions,boolean answeringEnabled,String answeringBaseUrl,String answeringModel,String answeringApiKey){}
}
