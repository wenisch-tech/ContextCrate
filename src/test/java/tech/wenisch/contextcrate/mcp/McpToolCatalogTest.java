package tech.wenisch.contextcrate.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpToolCatalogTest {
  private final McpToolCatalog catalog = new McpToolCatalog();

  private static List<String> names(List<Map<String, Object>> tools) {
    return tools.stream().map(tool -> String.valueOf(tool.get("name"))).toList();
  }

  private static Map<String, Object> byName(List<Map<String, Object>> tools, String name) {
    return tools.stream().filter(tool -> name.equals(tool.get("name"))).findFirst().orElseThrow();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> schema(Map<String, Object> tool) {
    return (Map<String, Object>) tool.get("inputSchema");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> properties(Map<String, Object> tool) {
    return (Map<String, Object>) schema(tool).get("properties");
  }

  @Test
  void allSixToolsAreAdvertised() {
    assertThat(names(catalog.tools())).containsExactlyInAnyOrder(
        "search_crate", "ask_crate", "fetch_document", "list_documents", "list_sources", "list_crates");
  }

  @Test
  void everyToolDeclaresAValidObjectSchema() {
    for (Map<String, Object> tool : catalog.tools()) {
      assertThat(schema(tool)).as(String.valueOf(tool.get("name"))).containsEntry("type", "object");
      assertThat(schema(tool)).containsKey("properties");
      assertThat(String.valueOf(tool.get("description"))).isNotBlank();
    }
  }

  @Test
  void theCrateArgumentIsOfferedOnEveryCrateScopedTool() {
    // The tool list is server-wide, so the argument has to exist even on the crate-bound endpoint;
    // there the path wins because McpCrateResolver prefers it.
    for (String name : List.of("search_crate", "ask_crate", "fetch_document", "list_documents", "list_sources"))
      assertThat(properties(byName(catalog.tools(), name))).as(name).containsKey("crate");
    assertThat(properties(byName(catalog.tools(), "list_crates"))).isEmpty();
  }

  @Test
  void requiredArgumentsAreDeclared() {
    assertThat(schema(byName(catalog.tools(), "search_crate"))).containsEntry("required", List.of("query"));
    assertThat(schema(byName(catalog.tools(), "ask_crate"))).containsEntry("required", List.of("question"));
    assertThat(schema(byName(catalog.tools(), "fetch_document")))
        .containsEntry("required", List.of("documentId"));
  }

  @Test
  void theSearchDescriptionAdmitsItIsNotExhaustive() {
    assertThat(String.valueOf(byName(catalog.tools(), "search_crate").get("description")))
        .contains("NOT exhaustive")
        .contains("list_documents");
  }

  @Test
  void askCrateWarnsThatItMayBeUnconfigured() {
    // Availability is per crate and the catalogue is not, so the tool is always advertised and
    // reports the problem at call time instead of vanishing from the list.
    assertThat(String.valueOf(byName(catalog.tools(), "ask_crate").get("description")))
        .contains("not configured");
  }

  @Test
  void theInstructionsExplainWhichToolAnswersWhichKindOfQuestion() {
    assertThat(catalog.instructions())
        .contains("not an exhaustive list")
        .contains("list_documents")
        .contains("list_sources")
        .contains("list_crates");
  }
}
