package tech.wenisch.contextcrate.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.answer.AnswerService;
import tech.wenisch.contextcrate.domain.Crate;
import tech.wenisch.contextcrate.domain.NormalizedDocument;
import tech.wenisch.contextcrate.domain.Source;
import tech.wenisch.contextcrate.domain.SourceConfiguration;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.repository.DocumentListRow;
import tech.wenisch.contextcrate.repository.DocumentSort;
import tech.wenisch.contextcrate.repository.NormalizedDocumentRepository;
import tech.wenisch.contextcrate.service.SourceConfigurationCodec;
import tech.wenisch.contextcrate.service.SourceService;
import tools.jackson.databind.JsonNode;

/**
 * Executes the MCP tools against ContextCrate's services.
 *
 * <p>Everything here runs on the request thread. {@code SecurityContextHolder} and {@code
 * CrateContext} are thread-locals, so no work may be handed to another thread.
 */
@Component
public class McpTools {
  /** Keeps a single result within a sane context budget; chunks run 1–4 KB apiece. */
  static final int SEARCH_CHARACTER_BUDGET = 40_000;

  static final int SEARCH_DEFAULT_LIMIT = 8;
  static final int SEARCH_MAX_LIMIT = 25;
  static final int DOCUMENT_DEFAULT_CHARACTERS = 20_000;
  static final int DOCUMENT_MAX_CHARACTERS = 60_000;
  static final int LIST_DEFAULT_LIMIT = 25;
  static final int LIST_MAX_LIMIT = 200;

  private final SearchIndex index;
  private final AnswerService answers;
  private final NormalizedDocumentRepository documents;
  private final SourceService sources;
  private final SourceConfigurationCodec sourceCodec;

  public McpTools(SearchIndex index, AnswerService answers, NormalizedDocumentRepository documents,
      SourceService sources, SourceConfigurationCodec sourceCodec) {
    this.index = index;
    this.answers = answers;
    this.documents = documents;
    this.sources = sources;
    this.sourceCodec = sourceCodec;
  }

  /**
   * Full-text retrieval. Calls the index in-process so the complete chunk text is available; the
   * REST search endpoint hides it behind {@code @JsonIgnore} and returns only a short snippet.
   */
  public Map<String, Object> search(Crate crate, JsonNode arguments) throws Exception {
    String query = text(arguments, "query");
    if (query == null || query.isBlank())
      return McpProtocol.toolFailure("The \"query\" argument is required and must not be empty.");
    int limit = bounded(integer(arguments, "limit", SEARCH_DEFAULT_LIMIT), 1, SEARCH_MAX_LIMIT);
    String mode = text(arguments, "mode");

    SearchIndex.SearchResults results =
        index.search(new SearchIndex.SearchRequest(crate.getId(), query, limit, null, "chunk", mode));
    List<SearchIndex.SearchHit> hits = results.hits();
    if (hits.isEmpty())
      return McpProtocol.toolResult(
          "No passages in \"" + crate.getName() + "\" matched \"" + query + "\".",
          Map.of("hits", List.of()));

    StringBuilder rendered = new StringBuilder();
    rendered
        .append("Showing the ")
        .append(hits.size())
        .append(" most relevant passages from \"")
        .append(crate.getName())
        .append("\", ranked by relevance");
    if (hits.size() >= limit) rendered.append("; more matches likely exist");
    rendered.append(".\n");

    List<Map<String, Object>> structured = new ArrayList<>();
    int remaining = SEARCH_CHARACTER_BUDGET;
    int included = 0;
    for (SearchIndex.SearchHit hit : hits) {
      String body = hit.content() == null ? hit.snippet() : hit.content();
      if (body == null) body = "";
      boolean truncated = body.length() > remaining;
      if (truncated) body = body.substring(0, Math.max(0, remaining));
      remaining -= body.length();
      included++;
      rendered
          .append("\n[")
          .append(included)
          .append("] ")
          .append(hit.title() == null || hit.title().isBlank() ? hit.sourceUri() : hit.title())
          .append(" — ")
          .append(McpCitations.displayUri(hit.sourceUri()))
          .append('\n')
          .append(body)
          .append('\n');
      structured.add(hitEntry(included, hit));
      if (truncated || remaining <= 0) {
        if (included < hits.size())
          rendered
              .append("\n(Character budget reached; ")
              .append(hits.size() - included)
              .append(" further passages were omitted. Narrow the query or lower \"limit\".)\n");
        break;
      }
    }
    return McpProtocol.toolResult(rendered.toString(), Map.of("hits", structured));
  }

  /** Grounded answer from ContextCrate's own RAG pipeline, with the citations the UI shows. */
  public Map<String, Object> ask(Crate crate, JsonNode arguments) throws Exception {
    String question = text(arguments, "question");
    if (question == null || question.isBlank())
      return McpProtocol.toolFailure("The \"question\" argument is required and must not be empty.");
    if (!answers.available(crate.getId()))
      return McpProtocol.toolFailure(
          "Answer generation is not configured for \""
              + crate.getName()
              + "\". Use search_crate to retrieve passages instead.");

    Integer maxSources = arguments != null && arguments.hasNonNull("maxSources")
        ? arguments.get("maxSources").asInt()
        : null;
    AnswerService.Prepared prepared =
        answers.prepare(new AnswerService.Request(crate.getId(), question, null, "chunk", null, maxSources, null));
    AnswerService.Result result = answers.generate(prepared);

    List<AnswerService.Source> cited = prepared.structuredSources() ? prepared.sources() : List.of();
    StringBuilder rendered = new StringBuilder(result.text() == null ? "" : result.text());
    if (result.verificationStatus() != null)
      rendered.append("\n\n").append(verification(result.verificationStatus()));
    rendered.append(McpCitations.text(cited));

    Map<String, Object> structured = new LinkedHashMap<>();
    structured.put("answer", result.text());
    structured.put("verificationStatus", result.verificationStatus());
    structured.put("sources", McpCitations.structured(cited));
    return McpProtocol.toolResult(rendered.toString(), structured);
  }

  /** Full body of one document, windowed so a large page cannot swamp the context. */
  public Map<String, Object> fetchDocument(Crate crate, JsonNode arguments) {
    String id = text(arguments, "documentId");
    UUID documentId;
    try {
      documentId = UUID.fromString(id == null ? "" : id.trim());
    } catch (IllegalArgumentException invalid) {
      return McpProtocol.toolFailure(
          "The \"documentId\" argument must be a UUID as returned by search_crate or list_documents.");
    }
    NormalizedDocument document = documents.findByIdAndCrateId(documentId, crate.getId()).orElse(null);
    if (document == null)
      return McpProtocol.toolFailure("No document " + documentId + " exists in \"" + crate.getName() + "\".");

    String body = document.getBody() == null ? "" : document.getBody();
    int offset = Math.max(0, integer(arguments, "offset", 0));
    int max = bounded(integer(arguments, "maxCharacters", DOCUMENT_DEFAULT_CHARACTERS), 1, DOCUMENT_MAX_CHARACTERS);
    int from = Math.min(offset, body.length());
    int to = Math.min(from + max, body.length());
    String window = body.substring(from, to);
    int rest = body.length() - to;

    StringBuilder rendered = new StringBuilder();
    rendered
        .append(document.getTitle() == null ? "(untitled)" : document.getTitle())
        .append("\n")
        .append(McpCitations.displayUri(document.getSourceUri()))
        .append("\n\n")
        .append(window);
    if (rest > 0)
      rendered
          .append("\n\n(")
          .append(rest)
          .append(" further characters. Call again with offset=")
          .append(to)
          .append(" to continue.)");

    Map<String, Object> structured = new LinkedHashMap<>();
    structured.put("documentId", document.getId().toString());
    structured.put("title", document.getTitle());
    structured.put("sourceUri", document.getSourceUri());
    structured.put("language", document.getLanguage());
    structured.put("versionNumber", document.getVersionNumber());
    structured.put("createdAt", String.valueOf(document.getCreatedAt()));
    structured.put("totalCharacters", body.length());
    structured.put("offset", from);
    structured.put("remainingCharacters", rest);
    return McpProtocol.toolResult(rendered.toString(), structured);
  }

  /** Complete, paginated document catalogue — the only tool that reports a true total. */
  public Map<String, Object> listDocuments(Crate crate, JsonNode arguments) {
    int limit = bounded(integer(arguments, "limit", LIST_DEFAULT_LIMIT), 1, LIST_MAX_LIMIT);
    int offset = Math.max(0, integer(arguments, "offset", 0));
    String query = text(arguments, "query");

    var page = documents.findCurrentPage(crate.getId(), query, null, DocumentSort.CREATED, Sort.Direction.DESC,
        PageRequest.of(offset / limit, limit));
    long total = page.getTotalElements();

    StringBuilder rendered = new StringBuilder();
    rendered
        .append("\"")
        .append(crate.getName())
        .append("\" contains ")
        .append(total)
        .append(query == null || query.isBlank() ? " documents" : " documents matching \"" + query + "\"")
        .append(". Showing ")
        .append(page.getNumberOfElements())
        .append(" of them.\n");

    List<Map<String, Object>> structured = new ArrayList<>();
    for (DocumentListRow row : page.getContent()) {
      NormalizedDocument document = row.document();
      rendered
          .append("\n- ")
          .append(document.getTitle() == null ? "(untitled)" : document.getTitle())
          .append(" — ")
          .append(McpCitations.displayUri(document.getSourceUri()))
          .append(" (")
          .append(row.chunkCount())
          .append(" chunks, id ")
          .append(document.getId())
          .append(')');
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("documentId", document.getId().toString());
      entry.put("title", document.getTitle());
      entry.put("sourceUri", document.getSourceUri());
      entry.put("chunkCount", row.chunkCount());
      entry.put("createdAt", String.valueOf(document.getCreatedAt()));
      structured.add(entry);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("total", total);
    payload.put("offset", offset);
    payload.put("documents", structured);
    return McpProtocol.toolResult(rendered.toString(), payload);
  }

  /**
   * Where a crate's content comes from. Secrets are stripped through the same codec round-trip that
   * {@code SourceApiController} uses; the raw configuration JSON is never exposed.
   */
  public Map<String, Object> listSources(Crate crate) {
    List<Source> found = sources.list(crate.getId());
    StringBuilder rendered = new StringBuilder();
    rendered
        .append("\"")
        .append(crate.getName())
        .append("\" is fed by ")
        .append(found.size())
        .append(found.size() == 1 ? " source." : " sources.");

    List<Map<String, Object>> structured = new ArrayList<>();
    for (Source source : found) {
      SourceConfiguration configuration =
          sourceCodec
              .read(source.getConfigurationJson(), source.getConnectorType())
              .withoutSecrets();
      String endpoint = configuration.git() != null
          ? configuration.git().repositoryUrl()
          : configuration.website() != null ? configuration.website().url() : null;
      rendered
          .append("\n- ")
          .append(source.getName())
          .append(" (")
          .append(source.getConnectorType())
          .append(source.isEnabled() ? "" : ", disabled")
          .append(") — ")
          .append(endpoint == null ? "no endpoint configured" : endpoint);
      if (source.getDescription() != null && !source.getDescription().isBlank())
        rendered.append("\n    ").append(source.getDescription());

      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("name", source.getName());
      entry.put("connectorType", source.getConnectorType().name());
      entry.put("description", source.getDescription());
      entry.put("endpoint", endpoint);
      entry.put("enabled", source.isEnabled());
      entry.put("ingestionJobCount", sources.jobCount(source.getId()));
      structured.add(entry);
    }
    return McpProtocol.toolResult(rendered.toString(), Map.of("sources", structured));
  }

  /** The crates this credential can address. */
  public Map<String, Object> listCrates(List<Crate> crates) {
    if (crates.isEmpty())
      return McpProtocol.toolResult("No crate is accessible with this credential.", Map.of("crates", List.of()));
    StringBuilder rendered = new StringBuilder("Accessible crates:\n");
    List<Map<String, Object>> structured = new ArrayList<>();
    for (Crate crate : crates) {
      rendered
          .append("\n- ")
          .append(crate.getName())
          .append(" (id ")
          .append(crate.getId())
          .append(", ")
          .append(crate.getStatus())
          .append(')');
      if (crate.getDescription() != null && !crate.getDescription().isBlank())
        rendered.append("\n    ").append(crate.getDescription());
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("id", crate.getId().toString());
      entry.put("name", crate.getName());
      entry.put("description", crate.getDescription());
      entry.put("status", crate.getStatus().name());
      structured.add(entry);
    }
    return McpProtocol.toolResult(rendered.toString(), Map.of("crates", structured));
  }

  private static Map<String, Object> hitEntry(int citation, SearchIndex.SearchHit hit) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("citation", citation);
    entry.put("title", hit.title());
    entry.put("sourceUri", hit.sourceUri());
    entry.put("documentId", hit.documentId() == null ? null : hit.documentId().toString());
    entry.put("chunkId", hit.id() == null ? null : hit.id().toString());
    entry.put("chunkOrdinal", hit.chunkOrdinal());
    entry.put("score", hit.score());
    return entry;
  }

  private static String verification(String status) {
    return switch (status.toLowerCase(Locale.ROOT)) {
      case "verified" -> "Answer verified against the retrieved sources.";
      case "revised" -> "Answer was revised to use only retrieved-source facts.";
      case "blocked" -> "Answer was blocked because unsupported claims were found.";
      case "unsupported" -> "Answer contains claims not supported by the retrieved sources.";
      case "unavailable" -> "Answer verification was unavailable; review the sources carefully.";
      default -> "Answer verification status is unavailable.";
    };
  }

  private static String text(JsonNode arguments, String field) {
    return arguments != null && arguments.hasNonNull(field) ? arguments.get(field).asString() : null;
  }

  private static int integer(JsonNode arguments, String field, int fallback) {
    return arguments != null && arguments.hasNonNull(field) ? arguments.get(field).asInt(fallback) : fallback;
  }

  private static int bounded(int value, int minimum, int maximum) {
    return Math.max(minimum, Math.min(value, maximum));
  }
}
