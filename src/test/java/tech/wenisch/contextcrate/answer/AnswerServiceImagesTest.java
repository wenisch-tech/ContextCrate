package tech.wenisch.contextcrate.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.answer.AnswerGenerationProvider.Image;
import tech.wenisch.contextcrate.answer.AnswerGenerationProvider.Message;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.repository.AuditLogRepository;
import tech.wenisch.contextcrate.repository.DocumentChunkRepository;

/** Pictures of image sources go to the answer model (and the verification), and a model without vision still answers. */
class AnswerServiceImagesTest {
  private final UUID crateId = UUID.randomUUID();
  private final AnswerGenerationProvider provider = mock(AnswerGenerationProvider.class);
  private final AnswerImageService imageService = mock(AnswerImageService.class);
  private final List<List<Message>> streamed = new ArrayList<>();
  private final List<List<Message>> completed = new ArrayList<>();
  private final Image diagram = new Image("image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G'}, 1);
  private AnswerService service;

  @BeforeEach
  void setUp() throws Exception {
    var properties = mock(ContextCrateProperties.class);
    when(properties.answering()).thenReturn(mock(ContextCrateProperties.Answering.class));
    doAnswer(i -> { streamed.add(i.getArgument(0)); ((Consumer<String>) i.getArgument(1)).accept("The diagram shows three services."); return null; })
        .when(provider).stream(any(), any());
    when(provider.complete(any())).thenAnswer(i -> { completed.add(i.getArgument(0)); return "yes"; });
    service = new AnswerService(mock(SearchIndex.class), mock(DocumentChunkRepository.class), provider,
        mock(AuditLogRepository.class), properties, mock(RagSettingsService.class));
    service.setImages(imageService);
    when(imageService.imagesFor(any())).thenReturn(List.of(diagram));
  }

  private AnswerService.Prepared prepared(boolean sendImages, boolean verification, boolean includeImages) {
    var source = new AnswerService.Source(1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "T", "https://example.test/diagram.png", 0, "s", 1f, "Image: a diagram");
    return new AnswerService.Prepared(crateId, "Show me the diagram", List.of(), "hybrid", List.of(source), "alice", false, true, true,
        verification, "revise-once", List.of("q"), List.of(), 0, SystemPrompts.Layers.NONE, sendImages, includeImages);
  }

  private AnswerService.Prepared prepared(boolean sendImages, boolean verification) {
    var source = new AnswerService.Source(1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "T", "https://example.test/diagram.png", 0, "s", 1f, "Image: a diagram");
    return new AnswerService.Prepared(crateId, "What does the diagram show?", List.of(), "hybrid", List.of(source), "alice", false, true, true,
        verification, "revise-once", List.of("q"), List.of(), 0, SystemPrompts.Layers.NONE, sendImages);
  }

  @Test
  void theSourceMessageCarriesThePicturesAndNothingElseDoes() throws Exception {
    var result = service.generate(prepared(true, false));

    assertThat(result.text()).isEqualTo("The diagram shows three services.");
    var messages = streamed.getFirst();
    assertThat(messages.get(0).images()).isEmpty(); // system prompt
    assertThat(messages.get(1).images()).containsExactly(diagram); // the retrieved sources
    assertThat(messages.get(1).content()).startsWith("UNTRUSTED RETRIEVED SOURCES");
    assertThat(messages.getLast().images()).isEmpty(); // the question
    assertThat(streamed).hasSize(1);
  }

  @Test
  void withTheOptionOffNoPictureIsLookedUpOrSent() throws Exception {
    service.generate(prepared(false, false));

    verifyNoInteractions(imageService);
    assertThat(streamed.getFirst()).allSatisfy(m -> assertThat(m.images()).isEmpty());
  }

  @Test
  void aCrateWithoutImageSourcesSendsPlainMessages() throws Exception {
    when(imageService.imagesFor(any())).thenReturn(List.of());

    service.generate(prepared(true, false));

    assertThat(streamed.getFirst()).allSatisfy(m -> assertThat(m.images()).isEmpty());
  }

  @Test
  void aModelThatRejectsImagesAnswersAgainWithoutThem() throws Exception {
    doAnswer(i -> { streamed.add(i.getArgument(0)); throw new IllegalStateException("Answer provider returned HTTP 400"); })
        .doAnswer(i -> { streamed.add(i.getArgument(0)); ((Consumer<String>) i.getArgument(1)).accept("Answer from the description."); return null; })
        .when(provider).stream(any(), any());

    var result = service.generate(prepared(true, true));

    assertThat(result.text()).isEqualTo("Answer from the description.");
    assertThat(streamed).hasSize(2);
    assertThat(streamed.get(0).get(1).images()).hasSize(1);
    assertThat(streamed.get(1)).allSatisfy(m -> assertThat(m.images()).isEmpty());
    // the verification follows the final request: no pictures either
    assertThat(completed.getFirst()).allSatisfy(m -> assertThat(m.images()).isEmpty());
  }

  @Test
  void aFailureAfterTextWasStreamedIsNotRetried() throws Exception {
    doAnswer(i -> { ((Consumer<String>) i.getArgument(1)).accept("Half an ans"); throw new IllegalStateException("connection reset"); })
        .when(provider).stream(any(), any());

    assertThatThrownBy(() -> service.generate(prepared(true, false))).hasMessageContaining("connection reset");
    verify(provider, times(1)).stream(any(), any());
  }

  @Test
  void aFailureWithoutPicturesIsNotRetried() throws Exception {
    when(imageService.imagesFor(any())).thenReturn(List.of());
    doThrow(new IllegalStateException("HTTP 500")).when(provider).stream(any(), any());

    assertThatThrownBy(() -> service.generate(prepared(true, false))).hasMessageContaining("HTTP 500");
    verify(provider, times(1)).stream(any(), any());
  }

  @Test
  void theVerificationSeesTheSamePicturesSoClaimsFromThemAreNotFlagged() throws Exception {
    var result = service.generate(prepared(true, true));

    assertThat(result.verificationStatus()).isEqualTo("verified");
    var verification = completed.getFirst();
    assertThat(verification.get(0).images()).isEmpty();
    assertThat(verification.get(1).images()).containsExactly(diagram);
    assertThat(verification.get(1).content()).contains("UNTRUSTED ANSWER");
  }

  @Test
  void aFailingImageLookupNeverBlocksTheAnswer() throws Exception {
    when(imageService.imagesFor(any())).thenThrow(new IllegalStateException("lookup failed"));

    var result = service.generate(prepared(true, false));

    assertThat(result.text()).isEqualTo("The diagram shows three services.");
    assertThat(streamed.getFirst()).allSatisfy(m -> assertThat(m.images()).isEmpty());
  }

  // ---- returning pictures to the client

  private void modelAnswers(String text) throws Exception {
    doAnswer(i -> { streamed.add(i.getArgument(0)); ((Consumer<String>) i.getArgument(1)).accept(text); return null; })
        .when(provider).stream(any(), any());
  }

  @Test
  void aPictureThePlacedMarkerPointsToIsEmbeddedForClientsAndNeverInThePlainText() throws Exception {
    modelAnswers("Here is the diagram:\n[[image:1]]\nIt has three services.");
    var collector = mock(tech.wenisch.contextcrate.evaluation.EvaluationCollector.class);
    service.setEvaluation(collector);

    var result = service.generate(prepared(true, false, true));

    assertThat(result.text()).isEqualTo("Here is the diagram:\n\nIt has three services.").doesNotContain("[[image");
    String png = java.util.Base64.getEncoder().encodeToString(diagram.data());
    assertThat(result.rendered()).contains("![Image from source 1](data:image/png;base64," + png + ")").doesNotContain("[[image");
    // the model was told how to place a picture, and evaluation never receives picture data
    assertThat(streamed.getFirst().get(0).content()).contains("[[image:n]]");
    verify(collector).offer(any(), org.mockito.ArgumentMatchers.eq("Here is the diagram:\n\nIt has three services."));
  }

  @Test
  void markersForUnknownOrRepeatedPicturesVanish() throws Exception {
    modelAnswers("A [[image:1]] B [[image:1]] C [[image:7]] D");

    var result = service.generate(prepared(true, false, true));

    assertThat(result.text()).isEqualTo("A B C D");
    assertThat(result.rendered()).startsWith("A\n![Image from source 1]").endsWith(")\n B C D").doesNotContain("[[image").doesNotContain("source 7");
    assertThat(result.rendered().split("data:image/png", -1)).hasSize(2); // shown once
  }

  @Test
  void withTheOptionOffTheModelIsNotToldAndStrayMarkersAreRemoved() throws Exception {
    modelAnswers("Shown: [[image:1]] done");

    var result = service.generate(prepared(true, false, false));

    assertThat(streamed.getFirst().get(0).content()).doesNotContain("[[image");
    assertThat(result.text()).isEqualTo("Shown: done");
    assertThat(result.rendered()).isEqualTo("Shown: done").doesNotContain("data:");
    assertThat(result.images()).isEmpty();
  }

  @Test
  void withoutPicturesNothingIsEmbeddedEvenIfTheModelWritesAMarker() throws Exception {
    when(imageService.imagesFor(any())).thenReturn(List.of());
    modelAnswers("Look [[image:1]] here");

    var result = service.generate(prepared(true, false, true));

    assertThat(result.rendered()).isEqualTo("Look here");
    assertThat(streamed.getFirst().get(0).content()).doesNotContain("[[image");
  }

  @Test
  void whenTheModelRejectedThePicturesNoPictureIsEmbedded() throws Exception {
    doAnswer(i -> { throw new IllegalStateException("HTTP 400"); })
        .doAnswer(i -> { ((Consumer<String>) i.getArgument(1)).accept("Text only [[image:1]]"); return null; })
        .when(provider).stream(any(), any());

    var result = service.generate(prepared(true, false, true));

    assertThat(result.rendered()).isEqualTo("Text only").doesNotContain("data:");
  }
}
