package tech.wenisch.contextcrate.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import tech.wenisch.contextcrate.answer.AnswerService;
import tech.wenisch.contextcrate.domain.Crate;
import tech.wenisch.contextcrate.domain.NormalizedDocument;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.repository.DocumentListRow;
import tech.wenisch.contextcrate.repository.NormalizedDocumentRepository;
import tech.wenisch.contextcrate.service.SourceConfigurationCodec;
import tech.wenisch.contextcrate.service.SourceService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class McpToolsTest {
  private final SearchIndex index = mock(SearchIndex.class);
  private final AnswerService answers = mock(AnswerService.class);
  private final NormalizedDocumentRepository documents = mock(NormalizedDocumentRepository.class);
  private final SourceService sources = mock(SourceService.class);
  private final SourceConfigurationCodec codec = mock(SourceConfigurationCodec.class);
  private final McpTools tools = new McpTools(index, answers, documents, sources, codec);
  private final JsonMapper mapper = JsonMapper.builder().build();

  private final Crate crate = new Crate(UUID.randomUUID(), "Product docs", "Manuals", UUID.randomUUID());

  private JsonNode arguments(String json) {
    return mapper.readTree(json);
  }

  private static SearchIndex.SearchHit hit(String snippet, String content) {
    return new SearchIndex.SearchHit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "chunk",
        "Install guide", "https://example.com/install", 3, snippet, 0.9f, null, null, null, content, content);
  }

  @SuppressWarnings("unchecked")
  private static String text(Map<String, Object> result) {
    var content = (List<Map<String, Object>>) result.get("content");
    return String.valueOf(content.getFirst().get("text"));
  }

  @Test
  void searchReturnsTheFullChunkTextRatherThanTheSnippet() throws Exception {
    String body = "The installer requires Java 25 and at least 2 GB of memory to complete.";
    when(index.search(any())).thenReturn(
        new SearchIndex.SearchResults("install", "hybrid", List.of(hit("The installer requires…", body))));

    Map<String, Object> result = tools.search(crate, arguments("{\"query\":\"install\"}"));

    // The REST endpoint hides content behind @JsonIgnore; the whole point of the tool is to expose it.
    assertThat(text(result)).contains(body);
    assertThat(result.get("isError")).isEqualTo(false);
  }

  @Test
  void searchStatesThatResultsAreRankedAndNotExhaustive() throws Exception {
    when(index.search(any())).thenReturn(
        new SearchIndex.SearchResults("install", "hybrid", List.of(hit("s", "body"))));

    assertThat(text(tools.search(crate, arguments("{\"query\":\"install\"}"))))
        .contains("most relevant")
        .contains("ranked by relevance")
        .contains("Product docs");
  }

  @Test
  void searchWarnsThatMoreMatchesLikelyExistWhenTheLimitIsFilled() throws Exception {
    when(index.search(any())).thenReturn(new SearchIndex.SearchResults("install", "hybrid",
        List.of(hit("s", "one"), hit("s", "two"))));

    assertThat(text(tools.search(crate, arguments("{\"query\":\"install\",\"limit\":2}"))))
        .contains("more matches likely exist");
  }

  @Test
  void searchStopsAtTheCharacterBudgetAndSaysSo() throws Exception {
    String huge = "x".repeat(McpTools.SEARCH_CHARACTER_BUDGET + 500);
    when(index.search(any())).thenReturn(new SearchIndex.SearchResults("q", "hybrid",
        List.of(hit("s", huge), hit("s", "second passage"))));

    String rendered = text(tools.search(crate, arguments("{\"query\":\"q\"}")));

    assertThat(rendered).contains("Character budget reached").doesNotContain("second passage");
  }

  @Test
  void anEmptyQueryIsAToolErrorSoTheModelCanRetry() throws Exception {
    Map<String, Object> result = tools.search(crate, arguments("{\"query\":\"  \"}"));

    assertThat(result.get("isError")).isEqualTo(true);
    assertThat(text(result)).contains("query");
    verify(index, never()).search(any());
  }

  @Test
  void askRefusesWhenAnsweringIsNotConfigured() throws Exception {
    when(answers.available(crate.getId())).thenReturn(false);

    Map<String, Object> result = tools.ask(crate, arguments("{\"question\":\"How do I install?\"}"));

    assertThat(result.get("isError")).isEqualTo(true);
    assertThat(text(result)).contains("search_crate");
    verify(answers, never()).generate(any());
  }

  @Test
  void fetchDocumentWindowsTheBodyAndReportsWhatIsLeft() {
    NormalizedDocument document = mock(NormalizedDocument.class);
    UUID id = UUID.randomUUID();
    when(document.getId()).thenReturn(id);
    when(document.getBody()).thenReturn("abcdefghij");
    when(document.getTitle()).thenReturn("Guide");
    when(document.getSourceUri()).thenReturn("https://example.com/g");
    when(documents.findByIdAndCrateId(id, crate.getId())).thenReturn(Optional.of(document));

    Map<String, Object> result =
        tools.fetchDocument(crate, arguments("{\"documentId\":\"" + id + "\",\"maxCharacters\":4}"));

    assertThat(text(result)).contains("abcd").contains("6 further characters").contains("offset=4");
    @SuppressWarnings("unchecked")
    var structured = (Map<String, Object>) result.get("structuredContent");
    assertThat(structured.get("remainingCharacters")).isEqualTo(6);
    assertThat(structured.get("totalCharacters")).isEqualTo(10);
  }

  @Test
  void fetchDocumentRejectsANonUuidWithoutTouchingTheRepository() {
    Map<String, Object> result = tools.fetchDocument(crate, arguments("{\"documentId\":\"not-a-uuid\"}"));

    assertThat(result.get("isError")).isEqualTo(true);
    verify(documents, never()).findByIdAndCrateId(any(), any());
  }

  @Test
  void listDocumentsReportsTheTrueTotalNotJustThePage() {
    NormalizedDocument document = mock(NormalizedDocument.class);
    when(document.getId()).thenReturn(UUID.randomUUID());
    when(document.getTitle()).thenReturn("Guide");
    when(document.getSourceUri()).thenReturn("https://example.com/g");
    when(documents.findCurrentPage(any(), any(), any(), any(), any(), any()))
        .thenReturn(new PageImpl<>(List.of(new DocumentListRow(document, 4L)),
            PageRequest.of(0, 25, Sort.Direction.DESC, "createdAt"), 137L));

    Map<String, Object> result = tools.listDocuments(crate, arguments("{}"));

    assertThat(text(result)).contains("contains 137 documents");
    @SuppressWarnings("unchecked")
    var structured = (Map<String, Object>) result.get("structuredContent");
    assertThat(structured.get("total")).isEqualTo(137L);
  }
}
