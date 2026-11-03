package tech.wenisch.contextcrate.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.answer.RagSettingsService;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.storage.Hashing;
class RetrievalPreparationServiceTest {
  @Test void failIndexingRejectsUnusableEvaluation(){var f=new Fixture("fail-indexing");assertThatThrownBy(()->f.service.prepare(f.crateId,List.of(f.chunk))).isInstanceOf(IllegalStateException.class);}
  @Test void skipChunkOmitsUnusableEvaluation(){var f=new Fixture("skip-chunk");assertThat(f.service.prepare(f.crateId,List.of(f.chunk))).isEmpty();}
  @Test void embedSourceChunkFallsBackToStandardRecord(){var f=new Fixture("embed-source-chunk");assertThat(f.service.prepare(f.crateId,List.of(f.chunk))).singleElement().satisfies(r->{assertThat(r.proposition()).isFalse();assertThat(r.retrievalText()).isEqualTo(f.chunk.getContent());});}
  @Test void headerIsPrependedToTheRetrievalTextOnly(){
    var crateId=UUID.randomUUID();var settings=mock(RagSettingsService.class);var headers=mock(ContextHeaderService.class);
    var rag=new RagSettings(crateId,false,true,true,true,true,true,"revise-once","hybrid","standard","fail-indexing",8);rag.updateContextHeader("title-summary-sections",1000);
    when(settings.current(crateId)).thenReturn(rag);
    var document=new NormalizedDocument(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"https://example.test","Doc",null,null,null,"Body",Hashing.sha256("Body"),"{}");document.assignCrate(crateId);
    var chunk=new DocumentChunk(UUID.randomUUID(),document.getId(),0,"Guide > Setup","Restart it after changing the certificate.",Hashing.sha256("Restart it after changing the certificate."));chunk.assignCrate(crateId);
    when(headers.contextFor(document,"title-summary-sections",1000)).thenReturn(new ContextHeaderService.Context("Nginx TLS guide","How to configure TLS."));
    var service=new RetrievalPreparationService(settings,mock(PropositionService.class),headers);
    assertThat(service.prepare(crateId,document,List.of(chunk))).singleElement().satisfies(r->{
      assertThat(r.retrievalText()).isEqualTo("Title: Nginx TLS guide\nSummary: How to configure TLS.\nSection: Guide > Setup\n\nRestart it after changing the certificate.");
      assertThat(r.sourceContent()).isEqualTo("Restart it after changing the certificate.");
      assertThat(r.contentHash()).isEqualTo(chunk.getContentHash());
      assertThat(r.proposition()).isFalse();});
  }
  @Test void unavailableHeaderLeavesTheChunkUntouched(){
    var crateId=UUID.randomUUID();var settings=mock(RagSettingsService.class);var headers=mock(ContextHeaderService.class);
    var rag=new RagSettings(crateId,false,true,true,true,true,true,"revise-once","hybrid","standard","fail-indexing",8);rag.updateContextHeader("title",1000);
    when(settings.current(crateId)).thenReturn(rag);
    var document=new NormalizedDocument(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"https://example.test","Doc",null,null,null,"Body",Hashing.sha256("Body"),"{}");document.assignCrate(crateId);
    var chunk=new DocumentChunk(UUID.randomUUID(),document.getId(),0,null,"Plain chunk",Hashing.sha256("Plain chunk"));chunk.assignCrate(crateId);
    var service=new RetrievalPreparationService(settings,mock(PropositionService.class),headers);
    assertThat(service.prepare(crateId,document,List.of(chunk))).singleElement().satisfies(r->assertThat(r.retrievalText()).isEqualTo("Plain chunk"));
  }
  @Test void offNeverCallsTheModel(){
    var crateId=UUID.randomUUID();var settings=mock(RagSettingsService.class);var headers=mock(ContextHeaderService.class);
    when(settings.current(crateId)).thenReturn(new RagSettings(crateId,false,true,true,true,true,true,"revise-once","hybrid","standard","fail-indexing",8));
    var chunk=new DocumentChunk(UUID.randomUUID(),UUID.randomUUID(),0,null,"Plain chunk",Hashing.sha256("Plain chunk"));chunk.assignCrate(crateId);
    new RetrievalPreparationService(settings,mock(PropositionService.class),headers).prepare(crateId,mock(NormalizedDocument.class),List.of(chunk));
    verifyNoInteractions(headers);
  }
  private static class Fixture{final UUID crateId=UUID.randomUUID();final DocumentChunk chunk=new DocumentChunk(UUID.randomUUID(),UUID.randomUUID(),0,null,"Source chunk",Hashing.sha256("Source chunk"));final RagSettingsService settings=mock(RagSettingsService.class);final PropositionService propositions=mock(PropositionService.class);final RetrievalPreparationService service=new RetrievalPreparationService(settings,propositions);Fixture(String policy){chunk.assignCrate(crateId);when(settings.current(crateId)).thenReturn(new RagSettings(crateId,false,true,true,true,true,true,"revise-once","hybrid","proposition",policy,8));var evaluation=new PropositionEvaluation(UUID.randomUUID(),crateId,chunk.getId(),chunk.getContentHash(),"fingerprint","model","failed","offline");when(propositions.evaluate(chunk)).thenReturn(new PropositionService.Outcome(evaluation,List.of(),false));}}
  @Test void enrichmentAddsOneQuestionRecordPerQuestionNextToTheChunkRecord(){
    var crateId=UUID.randomUUID();var settings=mock(RagSettingsService.class);var questions=mock(QuestionGenerationService.class);
    var rag=new RagSettings(crateId,false,true,true,true,true,true,"revise-once","hybrid","standard","fail-indexing",8);rag.updateEnrichment(true,3);
    when(settings.current(crateId)).thenReturn(rag);
    var chunk=new DocumentChunk(UUID.randomUUID(),UUID.randomUUID(),4,"Guide","Engineers hand the pager over on Monday.",Hashing.sha256("x"));chunk.assignCrate(crateId);
    when(questions.questionsFor(crateId,List.of(chunk),3)).thenReturn(Map.of(chunk.getId(),List.of("When is the handover?","Who holds the pager?")));
    var service=new RetrievalPreparationService(settings,mock(PropositionService.class));service.setQuestions(questions);

    var records=service.prepare(crateId,List.of(chunk));

    assertThat(records).hasSize(3);
    assertThat(records.get(0).isQuestion()).isFalse();
    assertThat(records.get(0).retrievalText()).isEqualTo(chunk.getContent());
    assertThat(records.subList(1,3)).allSatisfy(r->{assertThat(r.isQuestion()).isTrue();assertThat(r.chunkId()).isEqualTo(chunk.getId());assertThat(r.recordId()).isNotEqualTo(chunk.getId());assertThat(r.sourceContent()).isEqualTo(chunk.getContent());assertThat(r.ordinal()).isEqualTo(4);});
    assertThat(records.subList(1,3)).extracting(r->r.retrievalText()).containsExactly("When is the handover?","Who holds the pager?");
    assertThat(records.stream().map(r->r.recordId()).distinct().count()).isEqualTo(3);
  }
  @Test void enrichmentOffNeverAsksForQuestions(){
    var crateId=UUID.randomUUID();var settings=mock(RagSettingsService.class);var questions=mock(QuestionGenerationService.class);
    when(settings.current(crateId)).thenReturn(new RagSettings(crateId,false,true,true,true,true,true,"revise-once","hybrid","standard","fail-indexing",8));
    var chunk=new DocumentChunk(UUID.randomUUID(),UUID.randomUUID(),0,null,"Plain chunk",Hashing.sha256("Plain chunk"));chunk.assignCrate(crateId);
    var service=new RetrievalPreparationService(settings,mock(PropositionService.class));service.setQuestions(questions);
    assertThat(service.prepare(crateId,List.of(chunk))).singleElement();
    verifyNoInteractions(questions);
  }
}
