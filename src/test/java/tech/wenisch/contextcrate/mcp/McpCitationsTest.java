package tech.wenisch.contextcrate.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.answer.AnswerService;

class McpCitationsTest {
  private static AnswerService.Source source(int citation, String title, String uri) {
    return new AnswerService.Source(citation, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        title, uri, 2, "a snippet", 0.8f, "the full chunk body");
  }

  @Test
  void citationsAppearInTheTextSoTheModelCanReproduceThem() {
    String rendered = McpCitations.text(List.of(source(1, "Install guide", "https://example.com/i")));

    assertThat(rendered).contains("[1]").contains("Install guide").contains("https://example.com/i")
        .contains("a snippet");
  }

  @Test
  void citationNumbersAreCarriedThroughUnchanged() {
    // AnswerService renumbers after grading and context budgeting; the [n] markers embedded in the
    // answer text refer to those numbers, so renumbering here would break every reference.
    var structured = McpCitations.structured(List.of(source(3, "Third", "https://example.com/3"),
        source(7, "Seventh", "https://example.com/7")));

    assertThat(structured).extracting(entry -> entry.get("citation")).containsExactly(3, 7);
  }

  @Test
  void theChunkBodyIsNeverIncludedInACitation() {
    var structured = McpCitations.structured(List.of(source(1, "Install guide", "https://example.com/i")));

    assertThat(structured.getFirst()).doesNotContainKey("content");
    assertThat(structured.getFirst().values()).doesNotContain("the full chunk body");
  }

  @Test
  void anEmptySourceListRendersNothing() {
    assertThat(McpCitations.text(List.of())).isEmpty();
  }

  @Test
  void gitSourceUrisAreUnwrappedForDisplayJustLikeTheDashboard() {
    String git = "git+https://git.example.com/team/product.git@"
        + "0123456789abcdef0123456789abcdef01234567/docs/install.md";

    assertThat(McpCitations.displayUri(git)).isEqualTo("https://git.example.com/team/product.git");
  }

  @Test
  void theRawSourceUriSurvivesInTheStructuredPayload() {
    String git = "git+https://git.example.com/team/product.git@"
        + "0123456789abcdef0123456789abcdef01234567/docs/install.md";

    var structured = McpCitations.structured(List.of(source(1, "Install", git)));

    assertThat(structured.getFirst().get("sourceUri")).isEqualTo(git);
  }

  @Test
  void nonHttpUrisAreLeftAlone() {
    assertThat(McpCitations.displayUri("file:///local/notes.md")).isEqualTo("file:///local/notes.md");
    assertThat(McpCitations.displayUri("not a uri at all")).isEqualTo("not a uri at all");
  }
}
