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
                    Map.of(
                        "kind",
                        Map.of("type", "keyword"),
                        "parent_id",
                        Map.of("type", "keyword"),
                        "run_id",
                        Map.of("type", "keyword"),
                        "url",
                        Map.of("type", "keyword"),
                        "title",
                        Map.of("type", "text"),
                        "language",
                        Map.of("type", "keyword"),
                        "text",
                        Map.of("type", "text"),
                        "content_hash",
                        Map.of("type", "keyword")))));
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
          Map.of(
              "kind",
              "chunk",
              "parent_id",
              d.getId().toString(),
              "run_id",
              d.getRunId().toString(),
              "url",
              d.getCanonicalUrl(),
              "title",
              nullable(d.getTitle()),
              "language",
              nullable(d.getLanguage()),
              "text",
              c.getContent(),
              "heading",
              nullable(c.getHeading()),
              "ordinal",
              c.getOrdinal(),
              "content_hash",
              c.getContentHash()));
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
}
