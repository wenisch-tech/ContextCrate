package tech.wenisch.harvex.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tech.wenisch.harvex.config.HarvexProperties;
import tech.wenisch.harvex.domain.DocumentChunk;
import tech.wenisch.harvex.domain.NormalizedDocument;

@Component
@ConditionalOnProperty(name = "harvex.index.backend", havingValue = "opensearch")
public class OpenSearchSearchIndex implements SearchIndex {
  private final HttpClient client = HttpClient.newHttpClient();
  private final ObjectMapper mapper;
  private final String endpoint, index;

  public OpenSearchSearchIndex(HarvexProperties p, ObjectMapper mapper) {
    this.mapper = mapper;
    endpoint = p.index().endpoint().replaceAll("/+$", "");
    index = p.index().prefix() + "-content-v1";
  }

  @Override
  public void initialize() throws IOException, InterruptedException {
    var mapping =
        mapper.writeValueAsString(
            Map.of(
                "mappings",
                Map.of(
                    "properties",
                    Map.ofEntries(
                      Map.entry("kind", Map.of("type", "keyword")),
                      Map.entry("parent_id", Map.of("type", "keyword")),
                      Map.entry("run_id", Map.of("type", "keyword")),
                      Map.entry("url", Map.of("type", "keyword")),
                      Map.entry("url_text", Map.of("type", "text")),
                      Map.entry("title", Map.of("type", "text")),
                      Map.entry("heading", Map.of("type", "text")),
                      Map.entry("ordinal", Map.of("type", "integer")),
                      Map.entry("language", Map.of("type", "keyword")),
                      Map.entry("text", Map.of("type", "text")),
                      Map.entry("content_hash", Map.of("type", "keyword"))))));
    request("PUT", "/" + index, mapping, Set.of(200, 400));
  }

  @Override
  public void upsert(NormalizedDocument d, List<DocumentChunk> chunks)
      throws IOException, InterruptedException {
    initialize();
    StringBuilder bulk = new StringBuilder();
    append(
        bulk,
        d.getId().toString(),
        Map.of(
            "kind",
            "document",
            "parent_id",
            d.getId().toString(),
            "run_id",
            d.getRunId().toString(),
            "url",
            d.getCanonicalUrl(),
            "url_text",
            d.getCanonicalUrl(),
            "title",
            nullable(d.getTitle()),
            "language",
            nullable(d.getLanguage()),
            "text",
            d.getBody(),
            "content_hash",
            d.getContentHash()));
    for (var c : chunks)
      append(
          bulk,
          c.getId().toString(),
            Map.ofEntries(
              Map.entry("kind", "chunk"),
              Map.entry("parent_id", d.getId().toString()),
              Map.entry("run_id", d.getRunId().toString()),
              Map.entry("url", d.getCanonicalUrl()),
              Map.entry("url_text", d.getCanonicalUrl()),
              Map.entry("title", nullable(d.getTitle())),
              Map.entry("language", nullable(d.getLanguage())),
              Map.entry("text", c.getContent()),
              Map.entry("heading", nullable(c.getHeading())),
              Map.entry("ordinal", c.getOrdinal()),
              Map.entry("content_hash", c.getContentHash())));
    request("POST", "/_bulk", bulk.toString(), Set.of(200));
  }

  private void append(StringBuilder b, String id, Map<String, Object> source) throws IOException {
    b.append(mapper.writeValueAsString(Map.of("index", Map.of("_index", index, "_id", id))))
        .append('\n')
        .append(mapper.writeValueAsString(source))
        .append('\n');
  }

  @Override
  public void delete(UUID id) throws IOException, InterruptedException {
    String query =
        mapper.writeValueAsString(
            Map.of("query", Map.of("term", Map.of("parent_id", id.toString()))));
    request("POST", "/" + index + "/_delete_by_query", query, Set.of(200));
  }

  @Override
  public SearchResults search(SearchRequest request) throws IOException, InterruptedException {
    initialize();
    if (request.query().isBlank()) return new SearchResults(request.query(), List.of());
    Map<String, Object> multiMatch =
        Map.of(
            "query", request.query(),
            "fields", List.of("title^3", "heading^2", "text", "url_text"));
    List<Map<String, Object>> filters = new ArrayList<>();
    if (request.kind() != null) filters.add(Map.of("term", Map.of("kind", request.kind())));
    if (request.runId() != null)
      filters.add(Map.of("term", Map.of("run_id", request.runId().toString())));
    Map<String, Object> query = new LinkedHashMap<>();
    query.put("must", List.of(Map.of("multi_match", multiMatch)));
    if (!filters.isEmpty()) query.put("filter", filters);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("size", request.limit());
    body.put("query", Map.of("bool", query));
    body.put("highlight", Map.of("fields", Map.of("text", Map.of(), "heading", Map.of())));
    var response = request("POST", "/" + index + "/_search", mapper.writeValueAsString(body), Set.of(200));
    var root = mapper.readTree(response).path("hits").path("hits");
    List<SearchHit> hits = new ArrayList<>();
    for (var hit : root) hits.add(hit(hit, request.query()));
    return new SearchResults(request.query(), hits);
  }

  @Override
  public void commit() throws IOException, InterruptedException {
    request("POST", "/" + index + "/_refresh", "", Set.of(200));
  }

  @Override
  public IndexHealth health() {
    try {
      var response = request("GET", "/" + index + "/_count", null, Set.of(200));
      long count = mapper.readTree(response).path("count").asLong();
      return new IndexHealth("opensearch", true, count, endpoint);
    } catch (Exception e) {
      return new IndexHealth("opensearch", false, 0, e.getMessage());
    }
  }

  private String request(String method, String path, String body, Set<Integer> expected)
      throws IOException, InterruptedException {
    var builder =
        HttpRequest.newBuilder(URI.create(endpoint + path))
            .header("Content-Type", "application/json");
    if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
    else builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    if (!expected.contains(response.statusCode()))
      throw new IOException(
          "OpenSearch "
              + method
              + " "
              + path
              + " returned "
              + response.statusCode()
              + ": "
              + response.body());
    return response.body();
  }

  private static String nullable(String value) {
    return value == null ? "" : value;
  }

  private static SearchHit hit(com.fasterxml.jackson.databind.JsonNode hit, String query) {
    var source = hit.path("_source");
    String kind = source.path("kind").asText();
    UUID documentId = UUID.fromString(source.path("parent_id").asText());
    UUID id = UUID.fromString("chunk".equals(kind) ? hit.path("_id").asText() : source.path("parent_id").asText());
    Integer ordinal = "chunk".equals(kind) ? source.path("ordinal").asInt() : null;
    return new SearchHit(
        id,
        documentId,
        UUID.fromString(source.path("run_id").asText()),
        kind,
        source.path("title").asText(null),
        source.path("url").asText(null),
        ordinal,
        snippet(hit, source.path("text").asText(""), query),
        (float) hit.path("_score").asDouble());
  }

  private static String snippet(com.fasterxml.jackson.databind.JsonNode hit, String text, String query) {
    var highlighted = hit.path("highlight").path("text");
    if (highlighted.isArray() && highlighted.size() > 0) return highlighted.get(0).asText();
    if (text.length() <= 240) return text;
    String lower = text.toLowerCase(Locale.ROOT);
    int match = -1;
    for (String term : query.toLowerCase(Locale.ROOT).split("\\s+")) {
      if (term.isBlank()) continue;
      match = lower.indexOf(term);
      if (match >= 0) break;
    }
    int start = match < 0 ? 0 : Math.max(0, match - 100);
    int end = Math.min(text.length(), start + 240);
    return (start > 0 ? "..." : "") + text.substring(start, end) + (end < text.length() ? "..." : "");
  }
}
