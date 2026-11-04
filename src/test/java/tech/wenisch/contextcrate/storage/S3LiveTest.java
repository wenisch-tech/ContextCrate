package tech.wenisch.contextcrate.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tech.wenisch.contextcrate.backend.ConnectionTest;
import tech.wenisch.contextcrate.backend.ConnectionTest.Status;
import tech.wenisch.contextcrate.backend.EndpointGuard;
import tech.wenisch.contextcrate.crawl.UrlPolicy;
import tech.wenisch.contextcrate.domain.CrateBackendSettings.S3Config;

/**
 * Against a real S3-compatible service, for example MinIO:
 * {@code S3_TEST_ENDPOINT=http://localhost:9000 S3_TEST_ACCESS_KEY=… S3_TEST_SECRET_KEY=… mvn test -Dtest=S3LiveTest}.
 */
@EnabledIfEnvironmentVariable(named = "S3_TEST_ENDPOINT", matches = "https?://.+")
class S3LiveTest {
  final String endpoint = System.getenv("S3_TEST_ENDPOINT"), accessKey = System.getenv("S3_TEST_ACCESS_KEY"), secretKey = System.getenv("S3_TEST_SECRET_KEY");
  final String bucket = "cc-test-" + UUID.randomUUID().toString().substring(0, 8);
  final StorageConnectionTester tester = new StorageConnectionTester(new EndpointGuard(new UrlPolicy(true)));

  S3Config config(String bucketName, String secret) { return new S3Config(endpoint, "us-east-1", bucketName, "docs", accessKey, secret, true); }

  @BeforeEach
  void createBucket() {
    try (var client = S3ArtifactStore.client(config(bucket, secretKey), 10)) { client.createBucket(b -> b.bucket(bucket)); }
  }

  @Test
  void theConnectionTestPassesForAWritableBucketAndLeavesNoObjectBehind() {
    ConnectionTest result = tester.test(config(bucket, secretKey));
    assertThat(result.ok()).as(result.toString()).isTrue();
    assertThat(result.steps()).extracting(ConnectionTest.Step::status).containsOnly(Status.OK);
    try (var client = S3ArtifactStore.client(config(bucket, secretKey), 10)) {
      assertThat(client.listObjectsV2(b -> b.bucket(bucket)).contents()).isEmpty();
    }
  }

  @Test
  void wrongSecretMissingBucketAndWrongEndpointAreExplained() {
    ConnectionTest denied = tester.test(config(bucket, "not-the-secret"));
    assertThat(denied.ok()).isFalse();
    assertThat(denied.steps().get(2).status()).isEqualTo(Status.FAILED);
    assertThat(denied.steps().get(2).detail()).containsIgnoringCase("denied");

    ConnectionTest missing = tester.test(config("no-such-bucket-" + UUID.randomUUID().toString().substring(0, 6), secretKey));
    assertThat(missing.steps().get(2).status()).isEqualTo(Status.FAILED);
    assertThat(missing.steps().get(2).detail()).contains("does not exist");
    assertThat(missing.steps().get(3).status()).isEqualTo(Status.SKIPPED);
  }

  @Test
  void aCratesOwnStoreWritesReadsAndPurgesUnderItsPrefix() throws Exception {
    var store = new S3ArtifactStore(config(bucket, secretKey));
    UUID crate = UUID.randomUUID();
    String key = "crates/" + crate + "/runs/r1/page.html";
    var saved = store.put(key, new ByteArrayInputStream("<html>hello</html>".getBytes()), 1000);
    assertThat(saved.length()).isEqualTo(18);
    assertThat(store.exists(key)).isTrue();
    assertThat(new String(store.open(key).readAllBytes())).isEqualTo("<html>hello</html>");
    store.put("crates/" + crate + "/runs/r1/other.html", new ByteArrayInputStream(new byte[] {1}), 10);
    store.deletePrefix("crates/" + crate);
    assertThat(store.exists(key)).isFalse();
    store.close();
  }
}
