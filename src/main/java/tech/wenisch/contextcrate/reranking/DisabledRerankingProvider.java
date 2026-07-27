package tech.wenisch.contextcrate.reranking;
import java.util.List;
/** Compatibility fallback used where reranking is deliberately not wired. */
public final class DisabledRerankingProvider implements RerankingProvider {
  @Override public boolean available(){return false;}
  @Override public int candidateLimit(){return 1;}
  @Override public List<Float> rerank(String query,List<String> documents){return List.of();}
}
