package tech.wenisch.contextcrate.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** A small in-process stand-in for the parts of the Qdrant REST API that ContextCrate uses. */
final class FakeQdrantServer implements AutoCloseable {
  record Point(String id, List<Double> dense, Map<Long, Double> sparse, JsonNode payload) {}

  final Map<String, JsonNode> configs = new LinkedHashMap<>();
  final Map<String, Map<String, Point>> collections = new LinkedHashMap<>();
  final List<String> apiKeys = new CopyOnWriteArrayList<>();
  final List<String> requests = new CopyOnWriteArrayList<>();
  volatile int failWith = 0;
  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpServer server;

  FakeQdrantServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
  }

  String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

  @Override public void close() { server.stop(0); }

  private void handle(HttpExchange e) throws IOException {
    String method = e.getRequestMethod(), path = e.getRequestURI().getPath();
    String key = e.getRequestHeaders().getFirst("api-key");
    if (key != null) apiKeys.add(key);
    requests.add(method + " " + path);
    JsonNode body = e.getRequestBody().available() >= 0 ? read(e) : null;
    if (failWith != 0) { reply(e, failWith, "{\"status\":{\"error\":\"boom\"}}"); return; }
    String[] p = path.split("/");
    if (path.equals("/")) { reply(e, 200, "{\"title\":\"qdrant - vector search engine\",\"version\":\"1.15.0\"}"); return; }
    synchronized (this) {
      if (p.length == 2 && method.equals("GET")) { // /collections
        List<Map<String, String>> list = new ArrayList<>();
        for (String name : collections.keySet()) list.add(Map.of("name", name));
        reply(e, 200, mapper.writeValueAsString(Map.of("result", Map.of("collections", list))));
        return;
      }
      String name = p[2];
      if (p.length == 3) {
        switch (method) {
          case "PUT" -> {
            if (collections.containsKey(name)) { reply(e, 409, "{\"status\":{\"error\":\"exists\"}}"); return; }
            collections.put(name, new LinkedHashMap<>());
            configs.put(name, body);
            reply(e, 200, "{\"result\":true}");
          }
          case "DELETE" -> { collections.remove(name); configs.remove(name); reply(e, 200, "{\"result\":true}"); }
          case "GET" -> {
            if (!collections.containsKey(name)) { reply(e, 404, "{}"); return; }
            JsonNode cfg = configs.get(name);
            reply(e, 200, mapper.writeValueAsString(Map.of("result", Map.of("points_count", collections.get(name).size(),
                "config", Map.of("params", Map.of("vectors", cfg.path("vectors"), "sparse_vectors", cfg.path("sparse_vectors")))))));
          }
          default -> reply(e, 405, "{}");
        }
        return;
      }
      Map<String, Point> points = collections.get(name);
      if (points == null) { reply(e, 404, "{\"status\":{\"error\":\"Not found: Collection `" + name + "` doesn't exist!\"}}"); return; }
      String action = p[3] + (p.length > 4 ? "/" + p[4] : "");
      switch (action) {
        case "index" -> reply(e, 200, "{\"result\":true}");
        case "points" -> {
          for (JsonNode n : body.path("points")) {
            Map<Long, Double> sparse = new LinkedHashMap<>();
            JsonNode sp = n.path("vector").path("text");
            for (int i = 0; i < sp.path("indices").size(); i++) sparse.put(sp.path("indices").get(i).asLong(), sp.path("values").get(i).asDouble());
            List<Double> dense = null;
            if (n.path("vector").has("dense")) { dense = new ArrayList<>(); for (JsonNode d : n.path("vector").path("dense")) dense.add(d.asDouble()); }
            points.put(n.path("id").asText(), new Point(n.path("id").asText(), dense, sparse, n.path("payload")));
          }
          reply(e, 200, "{\"result\":{\"status\":\"completed\"}}");
        }
        case "points/delete" -> { points.values().removeIf(pt -> matches(pt, body.path("filter"))); reply(e, 200, "{\"result\":{\"status\":\"completed\"}}"); }
        case "points/query" -> reply(e, 200, mapper.writeValueAsString(Map.of("result", Map.of("points", query(name, points, body)))));
        default -> reply(e, 404, "{}");
      }
    }
  }

  private List<Map<String, Object>> query(String collection, Map<String, Point> points, JsonNode body) {
    boolean sparse = body.path("query").isObject();
    String using = body.path("using").asText();
    List<Map<String, Object>> scored = new ArrayList<>();
    Map<Long, Double> idf = new HashMap<>();
    if (sparse) {
      for (Point pt : points.values()) for (Long term : pt.sparse().keySet()) idf.merge(term, 1.0, Double::sum);
    }
    for (Point pt : points.values()) {
      if (!matches(pt, body.path("filter"))) continue;
      double score = 0;
      if (sparse) {
        JsonNode q = body.path("query");
        for (int i = 0; i < q.path("indices").size(); i++) {
          Double weight = pt.sparse().get(q.path("indices").get(i).asLong());
          if (weight != null) score += q.path("values").get(i).asDouble() * weight * Math.log(1 + (points.size() - idf.get(q.path("indices").get(i).asLong()) + 0.5) / (idf.get(q.path("indices").get(i).asLong()) + 0.5));
        }
        if (score <= 0) continue;
      } else {
        if (!using.equals("dense") || pt.dense() == null) continue;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < pt.dense().size(); i++) { double a = body.path("query").get(i).asDouble(), b = pt.dense().get(i); dot += a * b; na += a * a; nb += b * b; }
        score = na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
      }
      scored.add(Map.of("id", pt.id(), "score", score, "payload", pt.payload()));
    }
    scored.sort(Comparator.comparing((Map<String, Object> m) -> (Double) m.get("score")).reversed());
    int limit = body.path("limit").asInt(10);
    return scored.subList(0, Math.min(limit, scored.size()));
  }

  private static boolean matches(Point point, JsonNode filter) {
    for (JsonNode condition : filter.path("must"))
      if (!point.payload().path(condition.path("key").asText()).asText().equals(condition.path("match").path("value").asText())) return false;
    return true;
  }

  private JsonNode read(HttpExchange e) throws IOException {
    byte[] bytes = e.getRequestBody().readAllBytes();
    return bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes);
  }

  private static void reply(HttpExchange e, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    e.getResponseHeaders().set("Content-Type", "application/json");
    e.sendResponseHeaders(status, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }
}
