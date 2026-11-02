package tech.wenisch.contextcrate.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tech.wenisch.contextcrate.metrics.CrateMetrics;

class HydeServiceTest {
  private final UUID crateId = UUID.randomUUID();
  private final AnswerGenerationProvider provider = mock(AnswerGenerationProvider.class);
  private final CrateMetrics metrics = mock(CrateMetrics.class);

  private HydeService service() {
    var service = new HydeService(provider);
    service.setMetrics(metrics);
    return service;
  }

  @Test
  void returnsThePassageAndSendsTheQuestionAsUntrustedData() throws Exception {
    when(provider.complete(any())).thenReturn("  Backups are created with pg_dump.  ");

    assertThat(service().generate(crateId, "how backup?")).isEqualTo("Backups are created with pg_dump.");

    var messages = ArgumentCaptor.forClass(List.class);
    verify(provider).complete(messages.capture());
    assertThat(messages.getValue().toString()).contains("untrusted data", "QUESTION:\nhow backup?");
    verify(metrics).count("answer.hyde", crateId, "outcome", "ok", "reason", "none");
    verify(metrics).count("llm.requests", crateId, "purpose", "hyde", "outcome", "ok");
    verify(metrics).summary(eq("answer.hyde.length"), eq(crateId), eq(33.0));
  }

  @Test
  void blankReplyMeansNoHypotheticalAnswer() throws Exception {
    when(provider.complete(any())).thenReturn("   ");

    assertThat(service().generate(crateId, "q")).isNull();
    verify(metrics).count("answer.hyde", crateId, "outcome", "failed", "reason", "empty");
  }

  @Test
  void providerFailureIsFailOpen() throws Exception {
    when(provider.complete(any())).thenThrow(new java.io.IOException("down"));

    assertThat(service().generate(crateId, "q")).isNull();
    verify(metrics).count("answer.hyde", crateId, "outcome", "failed", "reason", "error");
    verify(metrics).count("llm.requests", crateId, "purpose", "hyde", "outcome", "error");
  }

  @Test
  void capsLengthAndStripsCodeFences() {
    assertThat(HydeService.clean("x".repeat(5_000))).hasSize(HydeService.MAX_CHARACTERS);
    assertThat(HydeService.clean("```text\nHello world\n```")).isEqualTo("Hello world");
    assertThat(HydeService.clean("```")).isNull();
    assertThat(HydeService.clean(null)).isNull();
  }
}
