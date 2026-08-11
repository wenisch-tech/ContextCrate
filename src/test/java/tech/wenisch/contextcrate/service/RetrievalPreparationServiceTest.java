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
  private static class Fixture{final UUID crateId=UUID.randomUUID();final DocumentChunk chunk=new DocumentChunk(UUID.randomUUID(),UUID.randomUUID(),0,null,"Source chunk",Hashing.sha256("Source chunk"));final RagSettingsService settings=mock(RagSettingsService.class);final PropositionService propositions=mock(PropositionService.class);final RetrievalPreparationService service=new RetrievalPreparationService(settings,propositions);Fixture(String policy){chunk.assignCrate(crateId);when(settings.current(crateId)).thenReturn(new RagSettings(crateId,false,true,true,true,true,true,"revise-once","hybrid","proposition",policy,8));var evaluation=new PropositionEvaluation(UUID.randomUUID(),crateId,chunk.getId(),chunk.getContentHash(),"fingerprint","model","failed","offline");when(propositions.evaluate(chunk)).thenReturn(new PropositionService.Outcome(evaluation,List.of(),false));}}
}
