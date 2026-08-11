package tech.wenisch.contextcrate.service;
import java.util.*;
import org.springframework.stereotype.Service;
import tech.wenisch.contextcrate.answer.RagSettingsService;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.index.ChunkRetrievalRecord;
@Service
public class RetrievalPreparationService {
  private final RagSettingsService settings;private final PropositionService propositions;
  public RetrievalPreparationService(RagSettingsService settings,PropositionService propositions){this.settings=settings;this.propositions=propositions;}
  public List<ChunkRetrievalRecord> prepare(UUID crateId,List<DocumentChunk> chunks){RagSettings rag=settings.current(crateId);if(!"proposition".equals(rag.getRetrievalStrategy()))return chunks.stream().map(ChunkRetrievalRecord::standard).toList();List<ChunkRetrievalRecord> records=new ArrayList<>();for(DocumentChunk chunk:chunks){var outcome=propositions.evaluate(chunk);var accepted=outcome.accepted();if(outcome.failed()||accepted.isEmpty()){switch(rag.getPropositionFailurePolicy()){case "skip-chunk"->{continue;}case "embed-source-chunk"->{records.add(ChunkRetrievalRecord.standard(chunk));continue;}default->throw new IllegalStateException(outcome.failed()?"Proposition evaluation failed for chunk "+chunk.getId()+": "+outcome.evaluation().getErrorMessage():"No proposition passed grading for chunk "+chunk.getId());}}for(var p:accepted)records.add(new ChunkRetrievalRecord(p.getId(),chunk.getId(),chunk.getOrdinal(),chunk.getHeading(),p.getProposition(),chunk.getContent(),chunk.getContentHash(),true));}return records;}
}
