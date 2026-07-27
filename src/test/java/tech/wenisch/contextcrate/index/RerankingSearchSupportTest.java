package tech.wenisch.contextcrate.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.*;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.reranking.RerankingProvider;

class RerankingSearchSupportTest {
  private final UUID crate=UUID.randomUUID();
  @Test void appliesScoresAfterRetrievalAndKeepsOnlyRequestedLimit() {
    var request=new SearchIndex.SearchRequest(crate,"question",1,null,"chunk","lexical");
    var hits=List.of(hit("first",1),hit("second",2));
    var provider=new Scores(true,List.of(0.1f,0.9f));
    var result=RerankingSearchSupport.apply(request,hits,provider);
    assertThat(result).hasSize(1);assertThat(result.getFirst().id()).isEqualTo(hits.get(1).id());
    assertThat(result.getFirst().rerankScore()).isEqualTo(0.9f);
  }
  @Test void fallsBackToRetrievalOrderWhenRerankerFails() {
    var request=new SearchIndex.SearchRequest(crate,"question",2,null,"chunk","lexical");
    var hits=List.of(hit("first",1),hit("second",2));
    var result=RerankingSearchSupport.apply(request,hits,new Scores(true,null));
    assertThat(result).extracting(SearchIndex.SearchHit::id).containsExactly(hits.get(0).id(),hits.get(1).id());
  }
  @Test void expandsCandidatePoolOnlyWhenEnabled() {
    var request=new SearchIndex.SearchRequest(crate,"question",5,null,"chunk","lexical");
    assertThat(RerankingSearchSupport.candidateLimit(request,new Scores(true,List.of()),100)).isEqualTo(30);
    assertThat(RerankingSearchSupport.candidateLimit(request,new Scores(false,List.of()),100)).isEqualTo(5);
  }
  private SearchIndex.SearchHit hit(String content,float score){UUID id=UUID.randomUUID();return new SearchIndex.SearchHit(id,UUID.randomUUID(),UUID.randomUUID(),"chunk",null,"https://example.test",0,content,score,score,null,null,content);}
  private static final class Scores implements RerankingProvider {
    private final boolean available;private final List<Float> scores; Scores(boolean available,List<Float> scores){this.available=available;this.scores=scores;}
    @Override public boolean available(){return available;} @Override public int candidateLimit(){return 30;}
    @Override public List<Float> rerank(String query,List<String> documents){if(scores==null)throw new IllegalStateException("offline");return scores;}
  }
}
