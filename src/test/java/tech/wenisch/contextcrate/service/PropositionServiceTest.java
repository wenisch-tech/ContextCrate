package tech.wenisch.contextcrate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.answer.AnswerGenerationProvider;
import tech.wenisch.contextcrate.config.RuntimeProviderSettings;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.repository.*;
import tech.wenisch.contextcrate.storage.Hashing;

class PropositionServiceTest {
  @Test void acceptsOnlyScoresAboveSevenAndUsesTwoFreshCalls()throws Exception{
    var fixture=new Fixture();
    when(fixture.llm.complete(anyList(),eq(0d))).thenReturn("{\"propositions\":[\"Ada Lovelace published notes in 1843.\",\"Ada Lovelace wrote notes.\"]}","{\"ratings\":[{\"ordinal\":0,\"fidelity\":8,\"context\":9,\"completeness\":10,\"focus\":8},{\"ordinal\":1,\"fidelity\":10,\"context\":10,\"completeness\":7,\"focus\":10}]}");
    var outcome=fixture.service.evaluate(fixture.chunk);
    assertThat(outcome.propositions()).hasSize(2);assertThat(outcome.accepted()).singleElement().extracting(ChunkProposition::getOrdinal).isEqualTo(0);verify(fixture.llm,times(2)).complete(anyList(),eq(0d));
  }
  @Test void reusesCompletedEvaluationWithMatchingFingerprint()throws Exception{
    var fixture=new Fixture();when(fixture.llm.complete(anyList(),eq(0d))).thenReturn("{\"propositions\":[\"Ada Lovelace published notes in 1843.\"]}","{\"ratings\":[{\"ordinal\":0,\"fidelity\":8,\"context\":8,\"completeness\":8,\"focus\":8}]}");var first=fixture.service.evaluate(fixture.chunk);assertThat(first.failed()).isFalse();
    reset(fixture.llm);when(fixture.evaluations.findByChunkId(fixture.chunk.getId())).thenReturn(Optional.of(first.evaluation()));when(fixture.propositions.findByChunkIdOrderByOrdinal(fixture.chunk.getId())).thenReturn(first.propositions());
    var cached=fixture.service.evaluate(fixture.chunk);assertThat(cached.cached()).isTrue();verifyNoInteractions(fixture.llm);
  }
  @Test void malformedGenerationIsPersistedAsFailure()throws Exception{
    var fixture=new Fixture();when(fixture.llm.complete(anyList(),eq(0d))).thenReturn("not-json");var outcome=fixture.service.evaluate(fixture.chunk);assertThat(outcome.failed()).isTrue();assertThat(outcome.evaluation().getErrorMessage()).isNotBlank();verify(fixture.evaluations).save(argThat(x->x.getStatus().equals("failed")));
  }
  private static class Fixture{
    final UUID crateId=UUID.randomUUID();final AnswerGenerationProvider llm=mock(AnswerGenerationProvider.class);final PropositionEvaluationRepository evaluations=mock(PropositionEvaluationRepository.class);final ChunkPropositionRepository propositions=mock(ChunkPropositionRepository.class);final RuntimeProviderSettings providers=mock(RuntimeProviderSettings.class);final DocumentChunk chunk;final PropositionService service;
    Fixture(){chunk=new DocumentChunk(UUID.randomUUID(),UUID.randomUUID(),0,null,"Ada Lovelace published detailed notes about Charles Babbage's Analytical Engine in 1843.",Hashing.sha256("Ada Lovelace published detailed notes about Charles Babbage's Analytical Engine in 1843."));chunk.assignCrate(crateId);when(providers.effectiveAnswer()).thenReturn(new RuntimeProviderSettings.Answering(true,"https://llm.test/v1","chat",null,null,30,0,1000));when(llm.available()).thenReturn(true);when(llm.model()).thenReturn("chat");when(evaluations.findByChunkId(chunk.getId())).thenReturn(Optional.empty());when(evaluations.save(any())).thenAnswer(x->x.getArgument(0));service=new PropositionService(llm,new ObjectMapper(),evaluations,propositions,providers);}
  }
}
