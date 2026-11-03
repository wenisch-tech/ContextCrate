package tech.wenisch.contextcrate.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.domain.DocumentChunk;
import tech.wenisch.contextcrate.repository.DocumentChunkRepository;
import tech.wenisch.contextcrate.storage.Hashing;

class ContextWindowServiceTest {
  private final UUID crateId = UUID.randomUUID();
  private final DocumentChunkRepository repository = mock(DocumentChunkRepository.class);
  private final ContextWindowService service = new ContextWindowService(repository);

  /** A document of ten chunks "c0 ... c9", none overlapping. */
  private List<DocumentChunk> document(UUID id, int count) {
    List<DocumentChunk> chunks = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      var chunk = new DocumentChunk(UUID.nameUUIDFromBytes((id + ":" + i).getBytes()), id, i, null, "chunk-" + i, Hashing.sha256("chunk-" + i)).overlap(0);
      chunk.assignCrate(crateId);
      chunks.add(chunk);
    }
    when(repository.findByDocumentIdAndCrateIdAndOrdinalBetweenOrderByOrdinal(eq(id), eq(crateId), anyInt(), anyInt())).thenAnswer(i -> {
      int from = i.getArgument(2), to = i.getArgument(3);
      return chunks.stream().filter(c -> c.getOrdinal() >= from && c.getOrdinal() <= to).toList();
    });
    return chunks;
  }

  private ContextWindowService.Anchor anchor(List<DocumentChunk> chunks, int index) {
    var c = chunks.get(index);
    return new ContextWindowService.Anchor(c.getId(), c.getDocumentId(), c.getOrdinal());
  }

  @Test
  void aWindowOfOneAddsTheChunkBeforeAndAfterInIndexOrder() {
    var doc = document(UUID.randomUUID(), 10);

    var windows = service.expand(crateId, List.of(anchor(doc, 5)), 1);

    assertThat(windows).singleElement().satisfies(w -> {
      assertThat(w.from()).isEqualTo(4);
      assertThat(w.to()).isEqualTo(6);
      assertThat(w.chunks()).isEqualTo(3);
      assertThat(w.added()).isEqualTo(2);
      assertThat(w.text()).isEqualTo("chunk-4\n\nchunk-5\n\nchunk-6");
    });
  }

  @Test
  void theWindowIsClampedAtTheStartAndTheEndOfTheDocument() {
    var doc = document(UUID.randomUUID(), 10);

    assertThat(service.expand(crateId, List.of(anchor(doc, 0)), 2).getFirst().text()).isEqualTo("chunk-0\n\nchunk-1\n\nchunk-2");
    assertThat(service.expand(crateId, List.of(anchor(doc, 9)), 2).getFirst().text()).isEqualTo("chunk-7\n\nchunk-8\n\nchunk-9");
  }

  @Test
  void neighbouringMatchesAreMergedIntoOneWindowWithoutRepeatedChunks() {
    var doc = document(UUID.randomUUID(), 12);
    // Matches at 3 and 5 (rank 0 and 1) with a window of 1 cover 2-4 and 4-6: one window 2-6. A far match stays separate.
    var windows = service.expand(crateId, List.of(anchor(doc, 5), anchor(doc, 10), anchor(doc, 3)), 1);

    assertThat(windows).hasSize(2);
    var first = windows.get(0);
    assertThat(first.primary()).isZero(); // the best ranked hit of the merged window
    assertThat(first.from()).isEqualTo(2);
    assertThat(first.to()).isEqualTo(6);
    assertThat(first.text()).isEqualTo("chunk-2\n\nchunk-3\n\nchunk-4\n\nchunk-5\n\nchunk-6");
    assertThat(first.added()).isEqualTo(3);
    assertThat(windows.get(1).primary()).isEqualTo(1);
    assertThat(windows.get(1).text()).isEqualTo("chunk-9\n\nchunk-10\n\nchunk-11");
  }

  @Test
  void matchesInDifferentDocumentsStaySeparate() {
    var a = document(UUID.randomUUID(), 5);
    var b = document(UUID.randomUUID(), 5);

    var windows = service.expand(crateId, List.of(anchor(a, 2), anchor(b, 2)), 1);

    assertThat(windows).hasSize(2);
    assertThat(windows).extracting(ContextWindowService.Window::primary).containsExactly(0, 1);
    assertThat(windows.get(0).documentId()).isNotEqualTo(windows.get(1).documentId());
  }

  @Test
  void overlapBetweenNeighboursIsRemoved() {
    UUID id = UUID.randomUUID();
    var first = new DocumentChunk(UUID.randomUUID(), id, 0, null, "A long sentence that ends with these shared words", Hashing.sha256("a")).overlap(0);
    var second = new DocumentChunk(UUID.randomUUID(), id, 1, null, "these shared words and then it continues", Hashing.sha256("b")).overlap("these shared words".length());
    when(repository.findByDocumentIdAndCrateIdAndOrdinalBetweenOrderByOrdinal(eq(id), eq(crateId), anyInt(), anyInt())).thenReturn(List.of(first, second));

    var windows = service.expand(crateId, List.of(new ContextWindowService.Anchor(second.getId(), id, 1)), 1);

    assertThat(windows.getFirst().text()).isEqualTo("A long sentence that ends with these shared words and then it continues");
  }

  @Test
  void imageDescriptionsAreNotPartOfTheRunningTextUnlessMatched() {
    UUID id = UUID.randomUUID();
    var text0 = new DocumentChunk(UUID.randomUUID(), id, 0, null, "before", Hashing.sha256("0")).overlap(0);
    var image = new DocumentChunk(UUID.randomUUID(), id, 1, null, "A diagram", Hashing.sha256("1")).overlap(0).describe("image", null, null, null, null);
    var text2 = new DocumentChunk(UUID.randomUUID(), id, 2, null, "after", Hashing.sha256("2")).overlap(0);
    when(repository.findByDocumentIdAndCrateIdAndOrdinalBetweenOrderByOrdinal(eq(id), eq(crateId), anyInt(), anyInt())).thenReturn(List.of(text0, image, text2));

    assertThat(service.expand(crateId, List.of(new ContextWindowService.Anchor(text0.getId(), id, 0)), 2).getFirst().text()).isEqualTo("before\n\nafter");
    assertThat(service.expand(crateId, List.of(new ContextWindowService.Anchor(image.getId(), id, 1)), 1).getFirst().text()).contains("A diagram");
  }

  @Test
  void anchorsWithoutAnIndexAreLeftAlone() {
    assertThat(service.expand(crateId, List.of(new ContextWindowService.Anchor(UUID.randomUUID(), UUID.randomUUID(), null)), 1)).isEmpty();
    verifyNoInteractions(repository);
  }

  @Test
  void fetchByIndexReturnsTheChunkWithItsNeighboursOrNullForAnUnknownIndex() {
    UUID id = UUID.randomUUID();
    document(id, 6);

    var fetched = service.fetch(crateId, id, 3, 1, 1);
    assertThat(fetched.text()).isEqualTo("chunk-2\n\nchunk-3\n\nchunk-4");
    assertThat(fetched.from()).isEqualTo(2);
    assertThat(fetched.to()).isEqualTo(4);
    assertThat(service.fetch(crateId, id, 3, 0, 0).text()).isEqualTo("chunk-3");
    assertThat(service.fetch(crateId, id, 99, 1, 1)).isNull();
    // The radius is capped.
    assertThat(service.fetch(crateId, id, 3, 50, 50).chunkCount()).isEqualTo(6);
  }
}
