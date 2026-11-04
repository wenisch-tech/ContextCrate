package tech.wenisch.contextcrate.index;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import tech.wenisch.contextcrate.config.ContextCrateProperties;

/** Minimal JSON-over-HTTP client for the search services: base URL, authentication headers and error reporting in one place. */
final class RestIndexClient {
  record Response(int status, String body) {}

  private final HttpClient client;
  private final String endpoint, service;
  private final Map<String, String> headers = new LinkedHashMap<>();

  /** @param service name used in error messages, for example {@code Qdrant} */
  RestIndexClient(String service, ContextCrateProperties.Index index, String apiKeyHeader) {
    this.service = service;
    this.endpoint = index.endpoint().replaceAll("/+$", "");
    HttpClient.Builder builder = HttpClient.newBuilder();
    if (index.trustAllCertificates()) builder.sslContext(trustAll());
    this.client = builder.build();
    if (index.apiKey() != null) {
      headers.put(apiKeyHeader.equals("Authorization") ? "Authorization" : apiKeyHeader,
          apiKeyHeader.equals("Authorization") ? "ApiKey " + index.apiKey() : index.apiKey());
    } else if (index.username() != null) {
      String credentials = index.username() + ":" + (index.password() == null ? "" : index.password());
      headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
    }
  }

  String endpoint() { return endpoint; }

  Response request(String method, String path, String body, Set<Integer> expected) throws IOException, InterruptedException {
    return request(method, path, body, "application/json", expected);
  }

  /** @param contentType for example {@code application/x-ndjson} for bulk requests */
  Response request(String method, String path, String body, String contentType, Set<Integer> expected) throws IOException, InterruptedException {
    var builder = HttpRequest.newBuilder(URI.create(endpoint + path)).header("Content-Type", contentType);
    headers.forEach(builder::header);
    builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    if (!expected.contains(response.statusCode()))
      throw new IOException(service + " " + method + " " + path + " returned " + response.statusCode() + ": " + response.body());
    return new Response(response.statusCode(), response.body());
  }

  /** An SSL context that accepts every certificate. For development only. */
  static SSLContext trustAll() {
    try {
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, new TrustManager[] {new X509TrustManager() {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
      }}, new SecureRandom());
      return context;
    } catch (Exception e) {
      throw new IllegalStateException("Could not create the SSL context", e);
    }
  }
}
