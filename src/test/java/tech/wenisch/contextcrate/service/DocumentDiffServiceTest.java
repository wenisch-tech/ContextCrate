package tech.wenisch.contextcrate.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.domain.NormalizedDocument;

class DocumentDiffServiceTest {
  @Test
  void rendersAddedRemovedAndUnchangedLines() {
    NormalizedDocument previous = document("alpha\nbeta\ngamma");
    NormalizedDocument current = document("alpha\nupdated\ngamma");

    String diff = new DocumentDiffService().unified(previous, current);

    assertThat(diff).contains("--- v1", "+++ v1", " alpha", "-beta", "+updated", " gamma");
  }

  private static NormalizedDocument document(String body) {
    return new NormalizedDocument(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        "https://example.test/document", "Document", "en", null, null, body, "hash", "{}");
  }
}
