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
  private CaptionService captionService;
  @org.springframework.beans.factory.annotation.Autowired(required=false) public void setCaptionService(CaptionService captionService){this.captionService=captionService;}
  private SectionSummaryService sectionSummaries;
  @org.springframework.beans.factory.annotation.Autowired(required=false) public void setSectionSummaries(SectionSummaryService sectionSummaries){this.sectionSummaries=sectionSummaries;}
  private tech.wenisch.contextcrate.graph.GraphExtractionService graphExtraction;
  @org.springframework.beans.factory.annotation.Autowired(required=false) public void setGraphExtraction(tech.wenisch.contextcrate.graph.GraphExtractionService graphExtraction){this.graphExtraction=graphExtraction;}
  private QuestionGenerationService questions;
  @org.springframework.beans.factory.annotation.Autowired(required=false) public void setQuestions(QuestionGenerationService questions){this.questions=questions;}
  /**
   * Records to embed and index: the chunks (or their propositions) and, with document enrichment on, one extra
   * record per generated question. A question record is embedded as the question but points at its chunk.
   */
  public List<ChunkRetrievalRecord> prepare(UUID crateId,NormalizedDocument document,List<DocumentChunk> chunks){
    RagSettings rag=settings.current(crateId);List<ChunkRetrievalRecord> records=base(crateId,rag,document,chunks);
    // Graph RAG: entities and relations of every chunk are stored for the knowledge graph; adds no index records.
    if(rag.isGraphExtractionEnabled()&&graphExtraction!=null&&!chunks.isEmpty())graphExtraction.extract(crateId,chunks);
    // Hierarchical retrieval: one summary record per section, on top of every chunk record.
    if(rag.isHierarchicalEnabled()&&sectionSummaries!=null&&document!=null&&!chunks.isEmpty()){records=new ArrayList<>(records);records.addAll(sectionSummaries.summariesFor(document,chunks));}
    // Captions: the model describes tables and diagram code; each caption is an extra record that points back at its chunk.
    if(rag.isCaptionsEnabled()&&captionService!=null&&!chunks.isEmpty()){
      var made=captionService.captionsFor(crateId,chunks);records=new ArrayList<>(records);
      for(DocumentChunk chunk:chunks){var caption=made.get(chunk.getId());if(caption!=null)records.add(ChunkRetrievalRecord.caption(chunk,caption.kind(),caption.text()));}
    }
    if(!rag.isEnrichmentEnabled()||questions==null||chunks.isEmpty())return records;
    var generated=questions.questionsFor(crateId,chunks,rag.getEnrichmentQuestions());List<ChunkRetrievalRecord> all=new ArrayList<>(records);
    for(DocumentChunk chunk:chunks){List<String> list=generated.getOrDefault(chunk.getId(),List.of());for(int i=0;i<list.size();i++)all.add(ChunkRetrievalRecord.question(chunk,i,list.get(i)));}
    return all;
  }
  private List<ChunkRetrievalRecord> base(UUID crateId,RagSettings rag,NormalizedDocument document,List<DocumentChunk> chunks){
    String variant=rag.getContextHeaderVariant();ContextHeaderService.Context context=document!=null&&headers!=null&&ContextHeaderService.enabled(variant)&&!chunks.isEmpty()?headers.contextFor(document,variant,rag.getContextHeaderWords()):null;
    java.util.function.Function<DocumentChunk,ChunkRetrievalRecord> standard=chunk->ChunkRetrievalRecord.standard(chunk,context==null?"":ContextHeaderService.header(variant,context,chunk.getHeading()));
    if(!"proposition".equals(rag.getRetrievalStrategy()))return chunks.stream().map(standard).toList();List<ChunkRetrievalRecord> records=new ArrayList<>();for(DocumentChunk chunk:chunks){var outcome=propositions.evaluate(chunk);var accepted=outcome.accepted();if(outcome.failed()||accepted.isEmpty()){switch(rag.getPropositionFailurePolicy()){case "skip-chunk"->{continue;}case "embed-source-chunk"->{records.add(standard.apply(chunk));continue;}default->throw new IllegalStateException(outcome.failed()?"Proposition evaluation failed for chunk "+chunk.getId()+": "+outcome.evaluation().getErrorMessage():"No proposition passed grading for chunk "+chunk.getId());}}for(var p:accepted)records.add(new ChunkRetrievalRecord(p.getId(),chunk.getId(),chunk.getOrdinal(),chunk.getHeading(),p.getProposition(),chunk.getContent(),chunk.getContentHash(),true));}return records;}
}
