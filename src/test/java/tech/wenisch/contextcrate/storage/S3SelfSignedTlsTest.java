package tech.wenisch.contextcrate.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.FileInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.wenisch.contextcrate.backend.ConnectionTest;
import tech.wenisch.contextcrate.backend.EndpointGuard;
import tech.wenisch.contextcrate.crawl.UrlPolicy;
import tech.wenisch.contextcrate.domain.CrateBackendSettings.S3Config;

/** An S3 endpoint with a self-signed certificate is refused unless the crate (or the installation) ignores certificate errors. */
class S3SelfSignedTlsTest {
  HttpsServer server;
  final StorageConnectionTester tester = new StorageConnectionTester(new EndpointGuard(new UrlPolicy(true)));

  @BeforeEach
  void start(@TempDir Path dir) throws Exception {
    Path store = dir.resolve("keystore.p12");
    String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
    Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "s3", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=s3.internal.test",
        "-validity", "2", "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "changeit", "-keypass", "changeit").redirectErrorStream(true).start();
    process.getInputStream().readAllBytes();
    assumeTrue(process.waitFor() == 0 && Files.exists(store), "keytool is not available");
    KeyStore keys = KeyStore.getInstance("PKCS12");
    try (var in = new FileInputStream(store.toFile())) { keys.load(in, "changeit".toCharArray()); }
    KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    factory.init(keys, "changeit".toCharArray());
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(factory.getKeyManagers(), null, null);
    server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(context));
    server.createContext("/", exchange -> {
      byte[] body = "<Error><Code>AccessDenied</Code><Message>denied</Message></Error>".getBytes();
      exchange.getResponseHeaders().add("Content-Type", "application/xml");
      exchange.sendResponseHeaders(403, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
  }

  @AfterEach
  void stop() { if (server != null) server.stop(0); }

  S3Config config(boolean trustAll) {
    return new S3Config("https://127.0.0.1:" + server.getAddress().getPort(), "us-east-1", "b", null, "key", "secret", true, trustAll);
  }

  @Test
  void aSelfSignedCertificateFailsTheReachableStepWithATlsHint() {
    // Another test in the same JVM may have switched certificate validation off installation-wide.
    assumeFalse(tech.wenisch.contextcrate.util.InsecureSsl.globalTrustAll(), "global trust-all is active in this JVM");
    ConnectionTest result = tester.test(config(false));
    assertThat(result.ok()).isFalse();
    var reachable = result.steps().get(1);
    assertThat(reachable.status()).isEqualTo(ConnectionTest.Status.FAILED);
    assertThat(reachable.detail()).contains("TLS");
  }

  @Test
  void ignoringCertificateErrorsGetsPastTheHandshake() {
    ConnectionTest result = tester.test(config(true));
    assertThat(result.steps().get(1).status()).isEqualTo(ConnectionTest.Status.OK);
    // The server answers 403 to everything, so the check after the handshake reports the denied access.
    assertThat(result.steps().get(2).status()).isEqualTo(ConnectionTest.Status.FAILED);
    assertThat(result.steps().get(2).detail()).contains("Access denied");
  }
}
