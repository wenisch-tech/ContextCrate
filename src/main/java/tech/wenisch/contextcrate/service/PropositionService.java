package tech.wenisch.contextcrate.service;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.answer.AnswerGenerationProvider;
import tech.wenisch.contextcrate.config.*;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.repository.*;

@Service
public class PropositionService {
  static final String PROMPT_VERSION="proposition-v1";
  private final AnswerGenerationProvider llm;private final ObjectMapper mapper;
  private final PropositionEvaluationRepository evaluations;private final ChunkPropositionRepository propositions;
  private final RuntimeProviderSettings providers;
  public PropositionService(AnswerGenerationProvider llm,ObjectMapper mapper,PropositionEvaluationRepository evaluations,ChunkPropositionRepository propositions,RuntimeProviderSettings providers){this.llm=llm;this.mapper=mapper;this.evaluations=evaluations;this.propositions=propositions;this.providers=providers;}

  @Transactional(propagation=Propagation.REQUIRES_NEW)
  public Outcome evaluate(DocumentChunk chunk){
    try(var ignored=CrateContext.use(chunk.getCrateId())){
      var answer=providers.effectiveAnswer();String fingerprint=fingerprint(chunk,answer);
      var cached=evaluations.findByChunkId(chunk.getId());
      if(cached.isPresent()&&cached.get().getFingerprint().equals(fingerprint)&&cached.get().getStatus().equals("completed"))
        return new Outcome(cached.get(),propositions.findByChunkIdOrderByOrdinal(chunk.getId()),true);
      cached.ifPresent(value->evaluations.delete(value));evaluations.flush();
      UUID evaluationId=UUID.randomUUID();
      try{
        if(!llm.available())throw new IllegalStateException("Answer model is unavailable for proposition retrieval");
        List<String> generated=generate(chunk.getContent());
        if(generated.isEmpty())throw new IllegalStateException("The model returned no propositions");
        List<Rating> ratings=grade(chunk.getContent(),generated);
        validateRatings(generated,ratings);
        var savedEvaluation=evaluations.save(new PropositionEvaluation(evaluationId,chunk.getCrateId(),chunk.getId(),chunk.getContentHash(),fingerprint,llm.model(),"completed",null));
        List<ChunkProposition> saved=new ArrayList<>();
        for(int i=0;i<generated.size();i++){Rating r=ratings.get(i);String text=generated.get(i).trim();saved.add(new ChunkProposition(stable(chunk.getId()+":"+i+":"+text),evaluationId,chunk.getCrateId(),chunk.getId(),i,text,r.fidelity(),r.context(),r.completeness(),r.focus()));}
        propositions.saveAll(saved);return new Outcome(savedEvaluation,List.copyOf(saved),false);
      }catch(Exception error){
        String detail=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
        var failed=evaluations.save(new PropositionEvaluation(evaluationId,chunk.getCrateId(),chunk.getId(),chunk.getContentHash(),fingerprint,answer.model(),"failed",detail));
        return new Outcome(failed,List.of(),false);
      }
    }catch(Exception error){throw new IllegalStateException("Could not evaluate propositions",error);}
  }

  private List<String> generate(String source)throws Exception{
    String system="""
        Create only valid, atomic propositions from the supplied source. Every proposition must: (1) describe one fact, (2) stand alone without other context, (3) replace pronouns and ambiguous references with full names, (4) retain all relevant dates, data, names, and qualifications, and (5) contain a clear subject, verb, and object. Do not add facts. Return JSON only using {\"propositions\":[\"...\"]}.
        """;
    String response=llm.complete(List.of(new AnswerGenerationProvider.Message("system",system),new AnswerGenerationProvider.Message("user","<source>\n"+source+"\n</source>")),0);
    JsonNode root=parse(response),array=root.path("propositions");if(!array.isArray())throw new IllegalArgumentException("Proposition response must contain a propositions array");
    List<String> out=new ArrayList<>();for(JsonNode item:array){if(!item.isTextual()||item.asText().isBlank())throw new IllegalArgumentException("Every proposition must be a non-empty string");out.add(item.asText().trim());}return out;
  }
  private List<Rating> grade(String source,List<String> values)throws Exception{
    String system="""
        Independently rate every proposition against the source on four integer scales from 1 to 10: fidelity (accurately returns the original statement), context (understandable without further context), completeness (includes all stated data, names, and qualifications), and focus (focused and free of unnecessary words). Return JSON only using {\"ratings\":[{\"ordinal\":0,\"fidelity\":1,\"context\":1,\"completeness\":1,\"focus\":1}]} with exactly one item per proposition.
        """;
    StringBuilder input=new StringBuilder("<source>\n").append(source).append("\n</source>\n<propositions>\n");for(int i=0;i<values.size();i++)input.append(i).append(": ").append(values.get(i)).append('\n');input.append("</propositions>");
    JsonNode array=parse(llm.complete(List.of(new AnswerGenerationProvider.Message("system",system),new AnswerGenerationProvider.Message("user",input.toString())),0)).path("ratings");if(!array.isArray())throw new IllegalArgumentException("Rating response must contain a ratings array");
    List<Rating> out=new ArrayList<>();for(JsonNode n:array)out.add(new Rating(n.path("ordinal").asInt(-1),requiredScore(n,"fidelity"),requiredScore(n,"context"),requiredScore(n,"completeness"),requiredScore(n,"focus")));out.sort(Comparator.comparingInt(Rating::ordinal));return out;
  }
  private static int requiredScore(JsonNode node,String name){JsonNode value=node.path(name);if(!value.isIntegralNumber()||value.asInt()<1||value.asInt()>10)throw new IllegalArgumentException(name+" score must be an integer from 1 to 10");return value.asInt();}
  private static void validateRatings(List<String> generated,List<Rating> ratings){if(ratings.size()!=generated.size())throw new IllegalArgumentException("The rating count does not match the proposition count");for(int i=0;i<ratings.size();i++)if(ratings.get(i).ordinal()!=i)throw new IllegalArgumentException("Ratings must identify every proposition ordinal exactly once");}
  private JsonNode parse(String raw)throws Exception{String value=raw==null?"":raw.trim();if(value.startsWith("```")){int newline=value.indexOf('\n'),end=value.lastIndexOf("```");if(newline>=0&&end>newline)value=value.substring(newline+1,end).trim();}return mapper.readTree(value);}
  private static String fingerprint(DocumentChunk chunk,RuntimeProviderSettings.Answering answer)throws Exception{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((PROMPT_VERSION+"\n"+chunk.getContentHash()+"\n"+answer.baseUrl()+"\n"+answer.model()).getBytes(StandardCharsets.UTF_8)));}
  private static UUID stable(String value){return UUID.nameUUIDFromBytes(("proposition:"+value).getBytes(StandardCharsets.UTF_8));}
  public record Outcome(PropositionEvaluation evaluation,List<ChunkProposition> propositions,boolean cached){public List<ChunkProposition> accepted(){return propositions.stream().filter(ChunkProposition::isAccepted).toList();}public boolean failed(){return !"completed".equals(evaluation.getStatus());}}
  private record Rating(int ordinal,int fidelity,int context,int completeness,int focus){}
}
