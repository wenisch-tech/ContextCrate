package tech.wenisch.contextcrate.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.repository.AuditLogRepository;
import tech.wenisch.contextcrate.repository.DocumentChunkRepository;

class AnswerServiceVerificationTest {
  private final UUID crateId = UUID.randomUUID();
  private final AnswerGenerationProvider provider = mock(AnswerGenerationProvider.class);
  private AnswerService service;

  @BeforeEach
  void setUp() throws Exception {
    var properties = mock(ContextCrateProperties.class);
    var answering = mock(ContextCrateProperties.Answering.class);
    when(properties.answering()).thenReturn(answering);
    doAnswer(invocation -> { ((Consumer<String>) invocation.getArgument(1)).accept("Generated answer."); return null; })
        .when(provider).stream(any(), any());
    service = new AnswerService(mock(SearchIndex.class), mock(DocumentChunkRepository.class), provider,
        mock(AuditLogRepository.class), properties, mock(RagSettingsService.class));
  }

  @Test
  void returnsVerifiedAnswerUnchanged() throws Exception {
    when(provider.complete(any())).thenReturn("yes");

    var result = service.generate(prepared("revise-once", true));

    assertThat(result).isEqualTo(new AnswerService.Result("Generated answer.", "verified"));
  }

  @Test
  void revisesUnsupportedAnswerOnce() throws Exception {
    when(provider.complete(any())).thenReturn("no", "Revised answer.");

    var result = service.generate(prepared("revise-once", true));

    assertThat(result).isEqualTo(new AnswerService.Result("Revised answer.", "revised"));
    verify(provider, times(2)).complete(any());
  }

  @Test
  void blocksUnsupportedAnswerWhenConfigured() throws Exception {
    when(provider.complete(any())).thenReturn("no");

    var result = service.generate(prepared("block-answer", true));

    assertThat(result).isEqualTo(new AnswerService.Result("No answer was found in the knowledge base.", "blocked"));
  }

  @Test
  void warnsWhenUnsupportedAnswerIsAllowed() throws Exception {
    when(provider.complete(any())).thenReturn("no");

    var result = service.generate(prepared("return-warning", true));

    assertThat(result.verificationStatus()).isEqualTo("unsupported");
    assertThat(result.text()).startsWith("Warning: this answer contains statements");
  }

  @Test
  void warnsWhenVerifierIsUnavailable() throws Exception {
    when(provider.complete(any())).thenThrow(new IllegalStateException("offline"));

    var result = service.generate(prepared("revise-once", true));

    assertThat(result.verificationStatus()).isEqualTo("unavailable");
    assertThat(result.text()).startsWith("Warning: this answer could not be verified");
  }

  @Test
  void skipsVerificationWhenDisabled() throws Exception {
    var result = service.generate(prepared("revise-once", false));

    assertThat(result).isEqualTo(new AnswerService.Result("Generated answer.", null));
    verify(provider, never()).complete(any());
  }

  private AnswerService.Prepared prepared(String action, boolean enabled) {
    var source = new AnswerService.Source(1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        "Source", "https://example.test", 0, "source snippet", 1f, "source content");
    return new AnswerService.Prepared(crateId, "Question", List.of(), "hybrid", List.of(source), "tester",
        false, true, true, enabled, action);
  }
}
