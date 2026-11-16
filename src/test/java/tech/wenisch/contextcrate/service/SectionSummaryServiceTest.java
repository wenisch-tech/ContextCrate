package tech.wenisch.contextcrate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.answer.AnswerGenerationProvider;
import tech.wenisch.contextcrate.config.RuntimeProviderSettings;
import tech.wenisch.contextcrate.domain.DocumentChunk;
import tech.wenisch.contextcrate.domain.NormalizedDocument;
import tech.wenisch.contextcrate.domain.SectionSummary;
import tech.wenisch.contextcrate.repository.SectionSummaryRepository;
import tech.wenisch.contextcrate.storage.Hashing;

class SectionSummaryServiceTest {
  private final UUID crateId = UUID.randomUUID();
  private final AnswerGenerationProvider llm = mock(AnswerGenerationProvider.class);
  private final SectionSummaryRepository repository = mock(SectionSummaryRepository.class);
  private final RuntimeProviderSettings providers = mock(RuntimeProviderSettings.class);
  private final SectionSummaryService service = new SectionSummaryService(llm, repository, providers);
  private final NormalizedDocument document = new NormalizedDocument(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
      "https://example.test/doc", "doc.md", null, null, null, "body", Hashing.sha256("body"), "{}");

  private DocumentChunk chunk(int ordinal, String heading, String text) {
    var c = new DocumentChunk(UUID.randomUUID(), document.getId(), ordinal, heading, text, Hashing.sha256(text));
    c.assignCrate(crateId);
    return c;
  }

  private static String longText(String word) { return (word + " ").repeat(120); }

  private void answerModel() {
    document.assignCrate(crateId);
    when(providers.effectiveAnswer()).thenReturn(new RuntimeProviderSettings.Answering(true, "https://llm.test", "m", null, null, 30, 0.0, 100));
    when(llm.available()).thenReturn(true);
    when(llm.model()).thenReturn("m");
    when(repository.findByDocumentId(document.getId())).thenReturn(List.of());
  }

  @Test
  void groupsConsecutiveChunksByTheFirstTwoHeadingLevels() {
    var chunks = List.of(chunk(0, null, "intro"), chunk(1, "A > B > C", "x"), chunk(2, "A > B > D", "y"),
        chunk(3, "A > E", "z"), chunk(4, "A > B", "again"));
    var sections = SectionSummaryService.sections(document.getId(), chunks);

    assertThat(sections).extracting(s -> s.heading()).containsExactly(null, "A > B", "A > E", "A > B");
    assertThat(sections.get(1).firstOrdinal()).isEqualTo(1);
    assertThat(sections.get(1).lastOrdinal()).isEqualTo(2);
    // The same heading in a later place is a different section with its own id.
    assertThat(sections.get(1).id()).isNotEqualTo(sections.get(3).id());
    assertThat(SectionSummaryService.sections(document.getId(), chunks).get(1).id()).isEqualTo(sections.get(1).id());
  }

  @Test
  void anImageCaptionStoredAfterTheTextBelongsToTheSectionOfItsImage() {
    var picture = chunk(3, "Billing", "Image: chart\nA chart.");
    picture.describe("image", null, null, null, null).anchoredAt(1);
    var chunks = List.of(chunk(0, "Ops > Pager", "a"), chunk(1, "Ops > Pager", "b"), chunk(2, "Billing > Invoices", "c"), picture);

    var sections = SectionSummaryService.sections(document.getId(), chunks);

    assertThat(sections).extracting(s -> s.heading()).containsExactly("Ops > Pager", "Billing > Invoices");
    assertThat(sections.get(0).chunks()).extracting(DocumentChunk::getOrdinal).containsExactly(0, 1, 3);
    // the stored position is where the image sits, not the end of the document
    assertThat(sections.get(0).firstOrdinal()).isZero();
    assertThat(sections.get(0).lastOrdinal()).isEqualTo(1);
    assertThat(sections.get(1).firstOrdinal()).isEqualTo(2);
    assertThat(sections.get(1).lastOrdinal()).isEqualTo(2);
  }

  @Test
  void summarizesEverySectionAndStoresItsPosition() throws Exception {
    answerModel();
    when(llm.complete(any())).thenReturn("{\"summary\":\"Explains the pager rotation.\"}");
    var chunks = List.of(chunk(0, "Ops > Pager", longText("pager")), chunk(1, "Ops > Pager > Rules", longText("rules")),
        chunk(2, "Billing > Invoices", longText("invoice")));

    var records = service.summariesFor(document, chunks);

    assertThat(records).hasSize(2).allMatch(ChunkRetrievalRecord_isSummary());
    assertThat(records.get(0).retrievalText()).contains("Section: Ops > Pager").contains("Explains the pager rotation.");
    assertThat(records.get(0).ordinal()).isEqualTo(0);
    var captor = org.mockito.ArgumentCaptor.forClass(SectionSummary.class);
    verify(repository, times(2)).save(captor.capture());
    var saved = captor.getAllValues();
    assertThat(saved).extracting(SectionSummary::getFirstOrdinal).containsExactly(0, 2);
    assertThat(saved).extracting(SectionSummary::getLastOrdinal).containsExactly(1, 2);
    assertThat(records.get(0).chunkId()).isEqualTo(saved.get(0).getId());
  }

  private static java.util.function.Predicate<tech.wenisch.contextcrate.index.ChunkRetrievalRecord> ChunkRetrievalRecord_isSummary() {
    return tech.wenisch.contextcrate.index.ChunkRetrievalRecord::isSummary;
  }

  @Test
  void reusesACachedSummaryWithoutCallingTheModel() throws Exception {
    answerModel();
    var chunks = List.of(chunk(0, "Ops > Pager", longText("pager")));
    var section = SectionSummaryService.sections(document.getId(), chunks).getFirst();
    String fingerprint = SectionSummaryService.fingerprint(section.text(), "Ops > Pager", "https://llm.test", "m");
    var stored = new SectionSummary(section.id(), crateId, document.getId(), "Ops > Pager", 5, 6, null, null, fingerprint, "m", "Cached summary");
    when(repository.findByDocumentId(document.getId())).thenReturn(List.of(stored));

    var records = service.summariesFor(document, chunks);

    verify(llm, never()).complete(any());
    assertThat(records).singleElement().satisfies(r -> assertThat(r.retrievalText()).contains("Cached summary"));
    // the stored position follows the section
    assertThat(stored.getFirstOrdinal()).isZero();
    assertThat(stored.getLastOrdinal()).isZero();
  }

  @Test
  void failedModelFallsBackToTheStartOfTheSectionAndIsNotCachedAsAModelResult() throws Exception {
    answerModel();
    when(llm.complete(any())).thenThrow(new IllegalStateException("down"));
    var chunks = List.of(chunk(0, "Ops > Pager", longText("pager")));

    var records = service.summariesFor(document, chunks);

    assertThat(records).singleElement().satisfies(r -> assertThat(r.retrievalText()).contains("pager pager").endsWith("..."));
    var saved = org.mockito.ArgumentCaptor.forClass(SectionSummary.class);
    verify(repository).save(saved.capture());
    assertThat(saved.getValue().getFingerprint()).startsWith("uncached:");
  }

  @Test
  void removesSectionsThatNoLongerExistAndParsesLenientReplies() {
    answerModel();
    var gone = new SectionSummary(UUID.randomUUID(), crateId, document.getId(), "Old", 0, 0, null, null, "f", "m", "old");
    when(repository.findByDocumentId(document.getId())).thenReturn(List.of(gone));
    when(llm.available()).thenReturn(false);

    service.summariesFor(document, List.of(chunk(0, "New", "short")));

    verify(repository).delete(gone);
    assertThat(service.parse("```json\n{\"summary\":\" Two   words \"}\n```")).isEqualTo("Two words");
    assertThat(service.parse("plain reply")).isEqualTo("plain reply");
    assertThat(service.parse("{\"other\":1}")).isNull();
  }
}
