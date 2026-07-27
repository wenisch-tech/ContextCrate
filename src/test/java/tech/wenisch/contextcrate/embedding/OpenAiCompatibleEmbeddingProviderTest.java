package tech.wenisch.contextcrate.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleEmbeddingProviderTest {
  @Test void recognizesGenericContextLimitWithoutGatewayName() {
    assertThat(OpenAiCompatibleEmbeddingProvider.isInputLimitError(400,
        "{\"error\":{\"message\":\"maximum context length is 512 input tokens\"}}"))
        .isTrue();
  }

  @Test void ignoresUnrelatedBadRequests() {
    assertThat(OpenAiCompatibleEmbeddingProvider.isInputLimitError(400,
        "{\"error\":{\"message\":\"model is unavailable\"}}"))
        .isFalse();
  }

  @Test void doesNotTreatNonBadRequestsAsInputLimits() {
    assertThat(OpenAiCompatibleEmbeddingProvider.isInputLimitError(500,
        "maximum context length is 512 input tokens")).isFalse();
  }
}
