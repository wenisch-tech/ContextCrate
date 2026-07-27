package tech.wenisch.contextcrate.index;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.wenisch.contextcrate.reranking.RerankingProvider;

final class RerankingSearchSupport {
  private static final Logger log=LoggerFactory.getLogger(RerankingSearchSupport.class);
  private RerankingSearchSupport() {}
  static int candidateLimit(SearchIndex.SearchRequest request,RerankingProvider provider,int backendLimit){return provider.available()?Math.min(backendLimit,Math.max(request.limit(),provider.candidateLimit())):request.limit();}
  static List<SearchIndex.SearchHit> apply(SearchIndex.SearchRequest request,List<SearchIndex.SearchHit> hits,RerankingProvider provider){
    if(!provider.available()||hits.isEmpty())return hits.stream().limit(request.limit()).toList();
    try {List<Float> scores=provider.rerank(request.query(),hits.stream().map(h->h.content()==null?h.snippet():h.content()).toList());if(scores.size()!=hits.size())throw new IllegalStateException("Reranker returned an unexpected score count");List<SearchIndex.SearchHit> scored=new ArrayList<>();for(int i=0;i<hits.size();i++){var h=hits.get(i);float score=scores.get(i);scored.add(new SearchIndex.SearchHit(h.id(),h.documentId(),h.runId(),h.kind(),h.title(),h.sourceUri(),h.chunkOrdinal(),h.snippet(),score,h.lexicalScore(),h.semanticScore(),score,h.content()));}return scored.stream().sorted(Comparator.comparing(SearchIndex.SearchHit::score).reversed().thenComparing(h->h.id().toString())).limit(request.limit()).toList();}catch(Exception e){log.warn("Reranking failed for crate {}; using retrieval order: {}",request.crateId(),e.getMessage());return hits.stream().limit(request.limit()).toList();}
  }
}
