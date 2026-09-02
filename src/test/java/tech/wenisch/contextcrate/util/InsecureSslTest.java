package tech.wenisch.contextcrate.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.security.KeyStore;
import java.time.Duration;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that {@link InsecureSsl#installGlobalTrustAll()} makes the JVM-default TLS stack
 * (used by every plain {@code java.net.http.HttpClient}, including the model providers, robots,
 * and Keycloak clients) accept a certificate signed by an untrusted/self-signed CA that the
 * default JVM trust store would otherwise reject.
 *
 * <p>The test certificate carries a SAN matching the loopback address it is served on, so the
 * assertion is independent of {@code jdk.internal.httpclient.disableHostnameVerification}: that
 * system property is read once into a static field the first time {@code java.net.http}'s
 * internal classes load, so setting it here has no effect once an earlier test in the same
 * surefire fork has already built an {@code HttpClient}. Production code is unaffected — {@code
 * InsecureTlsEnvironmentPostProcessor} sets it before the application creates its first
 * {@code HttpClient} — but this test suite cannot rely on that ordering.
 *
 * <p>Installation is process-wide and irreversible for the life of the JVM. Other test classes in
 * this module may install it too, so this class only asserts the post-install behavior rather
 * than a "before install, handshakes fail" baseline that cross-class ordering cannot guarantee.
 */
class InsecureSslTest {
  private static HttpsServer server;
  private static String baseUrl;

  @BeforeAll
  static void startSelfSignedServer(@TempDir File tempDir) throws Exception {
    File keystoreFile = new File(tempDir, "keystore.p12");
    Process keytool =
        new ProcessBuilder(
                "keytool",
                "-genkeypair",
                "-alias", "insecure-ssl-test",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "1",
                "-storetype", "PKCS12",
                "-keystore", keystoreFile.getAbsolutePath(),
                "-storepass", "changeit",
                "-keypass", "changeit",
                "-dname", "CN=insecure-ssl-test.invalid",
                "-ext", "san=ip:127.0.0.1")
            .redirectErrorStream(true)
            .start();
    keytool.waitFor();
    assertThat(keytool.exitValue()).as("keytool self-signed cert generation").isZero();

    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    try (var in = Files.newInputStream(keystoreFile.toPath())) {
      keyStore.load(in, "changeit".toCharArray());
    }
    KeyManagerFactory keyManagerFactory =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagerFactory.init(keyStore, "changeit".toCharArray());
    SSLContext serverContext = SSLContext.getInstance("TLS");
    serverContext.init(keyManagerFactory.getKeyManagers(), null, null);

    server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
    server.createContext(
        "/",
        exchange -> {
          byte[] body = "ok".getBytes();
          exchange.sendResponseHeaders(200, body.length);
          try (var out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    baseUrl = "https://127.0.0.1:" + server.getAddress().getPort() + "/";
  }

  @AfterAll
  static void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void installGlobalTrustAllAcceptsSelfSignedCertificateProcessWide() throws Exception {
    InsecureSsl.installGlobalTrustAll();
    assertThat(InsecureSsl.globalTrustAll()).isTrue();

    // A fresh HttpClient with no explicit sslContext resolves through SSLContext.getDefault(),
    // exactly like the model/embedding/reranking/robots/Keycloak clients.
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("ok");
  }

  @Test
  void installGlobalTrustAllIsIdempotent() {
    InsecureSsl.installGlobalTrustAll();
    InsecureSsl.installGlobalTrustAll();
    assertThat(InsecureSsl.globalTrustAll()).isTrue();
  }
}
