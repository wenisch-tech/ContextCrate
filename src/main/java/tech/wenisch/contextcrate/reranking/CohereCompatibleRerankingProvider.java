package tech.wenisch.contextcrate.reranking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.config.RuntimeProviderSettings;

@Component
public class CohereCompatibleRerankingProvider implements RerankingProvider {
  private final RuntimeProviderSettings settings; private final ObjectMapper mapper;
  public CohereCompatibleRerankingProvider(RuntimeProviderSettings settings,ObjectMapper mapper){this.settings=settings;this.mapper=mapper;}
  @Override public boolean available(){var c=settings.effectiveReranking();return c.enabled()&&c.cohereBaseUrl()!=null&&!c.cohereBaseUrl().isBlank()&&c.cohereModel()!=null&&!c.cohereModel().isBlank();}
  @Override public int candidateLimit(){return settings.effectiveReranking().candidateLimit();}
  @Override public List<Float> rerank(String query,List<String> documents)throws Exception{
    var c=settings.effectiveReranking(); if(!available())throw new IllegalStateException("Cohere-compatible reranking endpoint and model must be configured");
    var request=HttpRequest.newBuilder(URI.create(c.cohereBaseUrl().replaceAll("/+$","")+"/v2/rerank")).timeout(Duration.ofSeconds(c.cohereTimeoutSeconds())).header("Content-Type","application/json");
    if(c.cohereApiKey()!=null&&!c.cohereApiKey().isBlank())request.header("Authorization","Bearer "+c.cohereApiKey());
    List<String> limited=documents.stream().map(value->limit(value,c.cohereMaxInputCharacters())).toList();
    String body=mapper.writeValueAsString(Map.of("model",c.cohereModel(),"query",query,"documents",limited,"top_n",limited.size()));
    var response=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(c.cohereTimeoutSeconds())).build().send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    if(response.statusCode()/100!=2)throw new IllegalStateException("Reranking endpoint returned HTTP "+response.statusCode());
    float[] scores=new float[limited.size()]; boolean[] seen=new boolean[limited.size()]; JsonNode results=mapper.readTree(response.body()).path("results");
    for(JsonNode result:results){int index=result.path("index").asInt(-1);if(index<0||index>=scores.length||seen[index])throw new IllegalStateException("Reranking endpoint returned invalid result indexes");seen[index]=true;scores[index]=(float)result.path("relevance_score").asDouble();}
    if(results.size()!=scores.length)throw new IllegalStateException("Reranking endpoint returned incomplete results");
    List<Float> answer=new ArrayList<>();for(float score:scores)answer.add(score);return answer;
  }
  private static String limit(String input,int max){String value=input==null?"":input;return value.codePointCount(0,value.length())<=max?value:value.substring(0,value.offsetByCodePoints(0,max));}
}
