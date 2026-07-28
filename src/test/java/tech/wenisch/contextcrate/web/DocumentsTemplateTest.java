package tech.wenisch.contextcrate.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DocumentsTemplateTest {
  @Test
  void catalogueIncludesSearchSortAndPagination() throws IOException {
    String html = template("documents.html");
    assertThat(html).contains("Search title or source URI", "sort='title'", "sort='uri'",
        "sort='chunks'", "sort='indexed'", "documentPage.totalPages");
  }

  @Test
  void detailEscapesChunkAndDiffContent() throws IOException {
    assertThat(template("document-details.html")).contains("<code th:text=\"${chunk.content}\">");
    assertThat(template("document-diff.html")).contains("<code th:text=\"${diff}\">");
  }

  private String template(String name) throws IOException {
    try (var input = getClass().getResourceAsStream("/templates/" + name)) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
