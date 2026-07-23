package tech.wenisch.harvex.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tech.wenisch.harvex.crawl.UrlPolicy;
import tech.wenisch.harvex.domain.CrawlConfiguration;

class KeycloakTokenServiceTest {
  @Test
  void rejectsMalformedSuccessfulResponse() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/realms/test/protocol/openid-connect/token",
        exchange -> {
          byte[] bytes = "not-json".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      var service = new KeycloakTokenService(new ObjectMapper(), new UrlPolicy(true));
      assertThatThrownBy(() -> service.request(oauth(base)))
          .hasMessage("OAuth2 token response was not valid JSON");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void appliesCrawlerUrlPolicyToTokenEndpoint() {
    var service = new KeycloakTokenService(new ObjectMapper(), new UrlPolicy(false));
    assertThatThrownBy(() -> service.request(oauth("http://127.0.0.1:1")))
        .isInstanceOf(SecurityException.class);
  }

  private static CrawlConfiguration.LoginConfiguration oauth(String baseUrl) {
    return new CrawlConfiguration.LoginConfiguration(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        false,
        baseUrl,
        "client",
        "secret",
        "test",
        CrawlConfiguration.AuthMethod.OAUTH2);
  }
}
