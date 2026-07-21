package tech.wenisch.harvex.embedding;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "harvex.embeddings.enabled", havingValue = "false")
public class DisabledEmbeddingProvider implements EmbeddingProvider {
  private static final ModelDescriptor DESCRIPTOR = new ModelDescriptor("disabled", "disabled", "none", 0, false, "", "");
  @Override public ModelDescriptor descriptor() { return DESCRIPTOR; }
  @Override public boolean available() { return false; }
  @Override public List<float[]> embedDocuments(List<String> texts) { throw new IllegalStateException("Embeddings are disabled"); }
}
