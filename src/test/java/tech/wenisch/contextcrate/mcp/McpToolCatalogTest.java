package tech.wenisch.contextcrate.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.answer.AnswerService;
import tech.wenisch.contextcrate.domain.Crate;

class McpToolCatalogTest {
  private final AnswerService answers = mock(AnswerService.class);
  private final McpToolCatalog catalog = new McpToolCatalog(answers);

  private final Crate crate = new Crate(UUID.randomUUID(), "Product docs",
      "Public product manuals and release notes", UUID.randomUUID());

  private static List<String> names(List<Map<String, Object>> tools) {
    return tools.stream().map(tool -> String.valueOf(tool.get("name"))).toList();
  }

  private static Map<String, Object> byName(List<Map<String, Object>> tools, String name) {
    return tools.stream().filter(tool -> name.equals(tool.get("name"))).findFirst().orElseThrow();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> properties(Map<String, Object> tool) {
    return (Map<String, Object>) ((Map<String, Object>) tool.get("inputSchema")).get("properties");
  }

  @Test
  void askCrateIsHiddenWhenAnsweringIsNotConfigured() {
    when(answers.available(crate.getId())).thenReturn(false);

    assertThat(names(catalog.tools(crate)))
        .contains("search_crate", "fetch_document", "list_documents", "list_sources")
        .doesNotContain("ask_crate");
  }

  @Test
  void askCrateAppearsOnceAnsweringIsConfigured() {
    when(answers.available(crate.getId())).thenReturn(true);

    assertThat(names(catalog.tools(crate))).contains("ask_crate");
  }

  @Test
  void theCrateArgumentExistsOnlyOnTheGlobalEndpoint() {
    when(answers.available(crate.getId())).thenReturn(true);

    assertThat(properties(byName(catalog.tools(crate), "search_crate"))).doesNotContainKey("crate");
    assertThat(properties(byName(catalog.tools(null), "search_crate"))).containsKey("crate");
  }

  @Test
  void listCratesExistsOnlyOnTheGlobalEndpoint() {
    when(answers.available(crate.getId())).thenReturn(true);

    assertThat(names(catalog.tools(null))).contains("list_crates");
    assertThat(names(catalog.tools(crate))).doesNotContain("list_crates");
  }

  @Test
  void theSearchDescriptionCarriesTheCrateIdentitySoTheModelKnowsWhenToUseIt() {
    when(answers.available(crate.getId())).thenReturn(true);

    String description = String.valueOf(byName(catalog.tools(crate), "search_crate").get("description"));

    assertThat(description)
        .contains("Product docs")
        .contains("Public product manuals and release notes");
  }

  @Test
  void theSearchDescriptionAdmitsItIsNotExhaustive() {
    when(answers.available(crate.getId())).thenReturn(true);

    assertThat(String.valueOf(byName(catalog.tools(crate), "search_crate").get("description")))
        .contains("NOT exhaustive")
        .contains("list_documents");
  }

  @Test
  void theInstructionsExplainWhichToolAnswersWhichKindOfQuestion() {
    String instructions = catalog.instructions(crate);

    assertThat(instructions)
        .contains("Product docs")
        .contains("not an exhaustive list")
        .contains("list_documents")
        .contains("list_sources");
  }

  @Test
  void everyToolDeclaresAValidObjectSchema() {
    when(answers.available(crate.getId())).thenReturn(true);

    for (Map<String, Object> tool : catalog.tools(null)) {
      @SuppressWarnings("unchecked")
      Map<String, Object> schema = (Map<String, Object>) tool.get("inputSchema");
      assertThat(schema).as(String.valueOf(tool.get("name"))).containsEntry("type", "object");
      assertThat(schema).containsKey("properties");
    }
  }
}
