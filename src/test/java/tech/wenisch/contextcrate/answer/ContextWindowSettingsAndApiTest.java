package tech.wenisch.contextcrate.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.CrateMember;
import tech.wenisch.contextcrate.domain.DocumentChunk;
import tech.wenisch.contextcrate.domain.NormalizedDocument;
import tech.wenisch.contextcrate.domain.RagSettings;
import tech.wenisch.contextcrate.repository.*;
import tech.wenisch.contextcrate.service.chunking.BlockPacker;
import tech.wenisch.contextcrate.service.chunking.Block;
import tech.wenisch.contextcrate.service.chunking.ChunkOptions;
import tech.wenisch.contextcrate.service.CrateAccessService;
import tech.wenisch.contextcrate.storage.Hashing;
import tech.wenisch.contextcrate.web.CrateOperationsApiController;

class ContextWindowSettingsAndApiTest {
  private final UUID crateId = UUID.randomUUID();

  @Test
  void theWindowIsOffByDefaultAndValidated() {
    var repository = mock(RagSettingsRepository.class);
    var stored = new RagSettings(crateId, false, true, true, true, true, true, "revise-once", "hybrid", 8);
    when(repository.findById(crateId)).thenReturn(Optional.of(stored));
    when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    var properties = mock(ContextCrateProperties.class);
    when(properties.answering()).thenReturn(mock(ContextCrateProperties.Answering.class));
    var service = new RagSettingsService(repository, properties);

    assertThat(stored.getContextWindow()).isZero();
    assertThat(service.updateContextWindow(crateId, 2).getContextWindow()).isEqualTo(2);
    assertThatThrownBy(() -> service.updateContextWindow(crateId, -1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.updateContextWindow(crateId, 6)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("0 and 5");
  }

  @Test
  void aLongParagraphIsCutWithTheOverlapRecordedAndJoinsBackToTheOriginal() {
    String paragraph = String.join(" ", java.util.Collections.nCopies(60, "escalation policy words"));
    var drafts = BlockPacker.pack(List.of(Block.prose(List.of("Guide"), paragraph)), new ChunkOptions(200, 50));

    assertThat(drafts.size()).isGreaterThan(2);
    assertThat(drafts.get(0).overlap()).isZero();
    assertThat(drafts.subList(1, drafts.size())).allMatch(d -> d.overlap() > 0 && d.overlap() <= 50);
    List<DocumentChunk> chunks = new java.util.ArrayList<>();
    for (var d : drafts) chunks.add(new DocumentChunk(UUID.randomUUID(), UUID.randomUUID(), chunks.size(), d.heading(), d.content(), Hashing.sha256(d.content())).overlap(d.overlap()));
    assertThat(ChunkJoiner.joinText(chunks)).isEqualTo(paragraph);
  }

  @Test
  void fetchByIndexReturnsTheJoinedWindowAndReportsUnknownIndexesAsNotFound() {
    var access = mock(CrateAccessService.class);
    var documents = mock(NormalizedDocumentRepository.class);
    var chunks = mock(DocumentChunkRepository.class);
    UUID documentId = UUID.randomUUID();
    when(documents.findByIdAndCrateId(documentId, crateId)).thenReturn(Optional.of(mock(NormalizedDocument.class)));
    var one = new DocumentChunk(UUID.randomUUID(), documentId, 1, null, "one", Hashing.sha256("1")).overlap(0);
    var two = new DocumentChunk(UUID.randomUUID(), documentId, 2, null, "two", Hashing.sha256("2")).overlap(0);
    when(chunks.findByDocumentIdAndCrateIdAndOrdinalBetweenOrderByOrdinal(eq(documentId), eq(crateId), anyInt(), anyInt())).thenAnswer(i -> {
      int from = i.getArgument(2), to = i.getArgument(3);
      return List.of(one, two).stream().filter(c -> c.getOrdinal() >= from && c.getOrdinal() <= to).toList();
    });
    var api = new CrateOperationsApiController(access, documents, chunks, mock(AuditLogRepository.class), mock(tech.wenisch.contextcrate.index.SearchIndex.class),
        mock(tech.wenisch.contextcrate.service.IndexRebuildService.class), mock(tech.wenisch.contextcrate.service.DocumentIndexRecoveryService.class),
        mock(CrateIndexGenerationRepository.class), mock(PropositionEvaluationRepository.class), mock(ChunkPropositionRepository.class));
    // package-private setter is reached through the Spring-style reflection-free path: the controller exposes it for injection
    org.springframework.test.util.ReflectionTestUtils.invokeMethod(api, "setContextWindow", new ContextWindowService(chunks));

    var fetched = api.chunkByIndex(crateId, documentId, 2, 1, 0);
    assertThat(fetched.text()).isEqualTo("one\n\ntwo");
    assertThat(fetched.index()).isEqualTo(2);
    assertThat(fetched.from()).isEqualTo(1);
    verify(access, atLeastOnce()).require(crateId, CrateMember.Role.VIEWER);
    assertThatThrownBy(() -> api.chunkByIndex(crateId, documentId, 9, 0, 0)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("No chunk with index 9");
  }
}
