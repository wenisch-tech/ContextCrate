package tech.wenisch.contextcrate.service;
import java.util.*;
import org.springframework.stereotype.Service;
import tech.wenisch.contextcrate.answer.RagSettingsService;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.index.ChunkRetrievalRecord;
@Service
public class RetrievalPreparationService {
  private final RagSettingsService settings;private final PropositionService propositions;private final ContextHeaderService headers;
  @org.springframework.beans.factory.annotation.Autowired
  public RetrievalPreparationService(RagSettingsService settings,PropositionService propositions,ContextHeaderService headers){this.settings=settings;this.propositions=propositions;this.headers=headers;}
  public RetrievalPreparationService(RagSettingsService settings,PropositionService propositions){this(settings,propositions,null);}
  /** Characters a contextual header can add to a chunk (title, summary and section are capped); 0 when headers are off. */
  public int headerReserve(UUID crateId){return ContextHeaderService.enabled(settings.current(crateId).getContextHeaderVariant())?900:0;}
  public List<ChunkRetrievalRecord> prepare(UUID crateId,List<DocumentChunk> chunks){return prepare(crateId,null,chunks);}
  /**
   * @param document the chunks' document; used for the contextual header, may be {@code null} (no header)
   */
  public List<ChunkRetrievalRecord> prepare(UUID crateId,NormalizedDocument document,List<DocumentChunk> chunks){RagSettings rag=settings.current(crateId);
    String variant=rag.getContextHeaderVariant();ContextHeaderService.Context context=document!=null&&headers!=null&&ContextHeaderService.enabled(variant)&&!chunks.isEmpty()?headers.contextFor(document,variant,rag.getContextHeaderWords()):null;
    java.util.function.Function<DocumentChunk,ChunkRetrievalRecord> standard=chunk->ChunkRetrievalRecord.standard(chunk,context==null?"":ContextHeaderService.header(variant,context,chunk.getHeading()));
    if(!"proposition".equals(rag.getRetrievalStrategy()))return chunks.stream().map(standard).toList();List<ChunkRetrievalRecord> records=new ArrayList<>();for(DocumentChunk chunk:chunks){var outcome=propositions.evaluate(chunk);var accepted=outcome.accepted();if(outcome.failed()||accepted.isEmpty()){switch(rag.getPropositionFailurePolicy()){case "skip-chunk"->{continue;}case "embed-source-chunk"->{records.add(standard.apply(chunk));continue;}default->throw new IllegalStateException(outcome.failed()?"Proposition evaluation failed for chunk "+chunk.getId()+": "+outcome.evaluation().getErrorMessage():"No proposition passed grading for chunk "+chunk.getId());}}for(var p:accepted)records.add(new ChunkRetrievalRecord(p.getId(),chunk.getId(),chunk.getOrdinal(),chunk.getHeading(),p.getProposition(),chunk.getContent(),chunk.getContentHash(),true));}return records;}
}
