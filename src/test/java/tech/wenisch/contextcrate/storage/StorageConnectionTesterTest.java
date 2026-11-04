package tech.wenisch.contextcrate.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.backend.ConnectionTest;
import tech.wenisch.contextcrate.backend.ConnectionTest.Status;
import tech.wenisch.contextcrate.backend.EndpointGuard;
import tech.wenisch.contextcrate.crawl.UrlPolicy;
import tech.wenisch.contextcrate.domain.CrateBackendSettings.S3Config;

class StorageConnectionTesterTest {
  final StorageConnectionTester strict = new StorageConnectionTester(new EndpointGuard(new UrlPolicy(false)));
  final StorageConnectionTester lenient = new StorageConnectionTester(new EndpointGuard(new UrlPolicy(true)));

  @Test
  void missingFieldsAreNamed() {
    ConnectionTest result = strict.test(new S3Config("http://s3.example.test", "us-east-1", "", null, "key", "secret", true));
    assertThat(result.ok()).isFalse();
    assertThat(result.steps().getFirst().detail()).contains("bucket");
    assertThat(result.steps().stream().skip(1)).allMatch(s -> s.status() == Status.SKIPPED);
    assertThat(strict.test(new S3Config("http://s3.example.test", "us-east-1", "b", null, "", "secret", true)).steps().getFirst().detail()).contains("access key");
    assertThat(strict.test(new S3Config("http://s3.example.test", "us-east-1", "b", null, "key", null, true)).steps().getFirst().detail()).contains("secret key");
  }

  @Test
  void privateEndpointsAreRefusedWithoutConnecting() {
    ConnectionTest result = strict.test(new S3Config("http://127.0.0.1:9000", "us-east-1", "b", null, "key", "secret", true));
    assertThat(result.steps().getFirst().status()).isEqualTo(Status.FAILED);
    assertThat(result.steps().getFirst().detail()).contains("Private network");
  }

  @Test
  void anUnreachableEndpointFailsAtTheReachableStep() {
    // port 1 on the loopback interface refuses connections immediately
    ConnectionTest result = lenient.test(new S3Config("http://127.0.0.1:1", "us-east-1", "b", null, "key", "secret", true));
    assertThat(result.ok()).isFalse();
    assertThat(result.steps().get(0).status()).isEqualTo(Status.OK);
    assertThat(result.steps().get(1).status()).isEqualTo(Status.FAILED);
    assertThat(result.steps().get(1).detail()).isNotBlank();
    assertThat(result.steps().stream().skip(2)).allMatch(s -> s.status() == Status.SKIPPED);
  }
}
