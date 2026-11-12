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
}
