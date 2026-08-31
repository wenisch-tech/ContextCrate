package tech.wenisch.contextcrate.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.answer.AnswerService;
import tech.wenisch.contextcrate.domain.ConnectorType;
import tech.wenisch.contextcrate.domain.Crate;
import tech.wenisch.contextcrate.domain.Source;
import tech.wenisch.contextcrate.domain.SourceConfiguration;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.repository.NormalizedDocumentRepository;
import tech.wenisch.contextcrate.service.ConfigurationCodec;
import tech.wenisch.contextcrate.service.SourceConfigurationCodec;
import tech.wenisch.contextcrate.service.SourceService;
import tools.jackson.databind.json.JsonMapper;

class McpListSourcesTest {
  private static final String SECRET = "ghp_a-git-token-that-must-never-escape";

  private final SourceService sources = mock(SourceService.class);
  private final SourceConfigurationCodec codec = new SourceConfigurationCodec(
      new com.fasterxml.jackson.databind.ObjectMapper(),
      new ConfigurationCodec(new com.fasterxml.jackson.databind.ObjectMapper()));
  private final McpTools tools = new McpTools(mock(SearchIndex.class), mock(AnswerService.class),
      mock(NormalizedDocumentRepository.class), sources, codec);

  private final Crate crate = new Crate(UUID.randomUUID(), "Product docs", null, UUID.randomUUID());

  @SuppressWarnings("unchecked")
  private static String text(Map<String, Object> result) {
    var content = (List<Map<String, Object>>) result.get("content");
    return String.valueOf(content.getFirst().get("text"));
  }

  @Test
  void gitSourcesAreListedWithoutLeakingCredentials() {
    // Credentials live in the configuration JSON; a raw dump would hand them to an external AI.
    String configuration = "{\"git\":{\"repositoryUrl\":\"https://git.example.com/team/product.git\","
        + "\"token\":\"" + SECRET + "\",\"username\":\"ci-bot\"}}";
    Source source = new Source(UUID.randomUUID(), crate.getId(), "Product repository",
        "Markdown documentation", ConnectorType.GIT, configuration);
    when(sources.list(crate.getId())).thenReturn(List.of(source));
    when(sources.jobCount(source.getId())).thenReturn(2L);

    Map<String, Object> result = tools.listSources(crate);
    String serialized = JsonMapper.builder().build().writeValueAsString(result);

    assertThat(serialized).doesNotContain(SECRET).doesNotContain("ci-bot");
    assertThat(text(result))
        .contains("Product repository")
        .contains("GIT")
        .contains("https://git.example.com/team/product.git");
  }

  @Test
  void connectorTypeEnabledFlagAndJobCountAreReported() {
    Source source = new Source(UUID.randomUUID(), crate.getId(), "Docs site", null,
        ConnectorType.HTTPS, "{\"website\":{\"url\":\"https://docs.example.com\"}}");
    when(sources.list(crate.getId())).thenReturn(List.of(source));
    when(sources.jobCount(source.getId())).thenReturn(3L);

    Map<String, Object> result = tools.listSources(crate);

    @SuppressWarnings("unchecked")
    var structured = (Map<String, Object>) result.get("structuredContent");
    @SuppressWarnings("unchecked")
    var listed = (List<Map<String, Object>>) structured.get("sources");
    assertThat(listed).singleElement().satisfies(entry -> {
      assertThat(entry).containsEntry("name", "Docs site");
      assertThat(entry).containsEntry("connectorType", "HTTPS");
      assertThat(entry).containsEntry("endpoint", "https://docs.example.com");
      assertThat(entry).containsEntry("enabled", true);
      assertThat(entry).containsEntry("ingestionJobCount", 3L);
    });
  }

  @Test
  void aCrateWithoutSourcesSaysSo() {
    when(sources.list(crate.getId())).thenReturn(List.of());

    assertThat(text(tools.listSources(crate))).contains("0 sources");
  }
}
