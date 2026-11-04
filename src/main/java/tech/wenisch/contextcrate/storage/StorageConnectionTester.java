package tech.wenisch.contextcrate.storage;

import java.util.UUID;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tech.wenisch.contextcrate.backend.ConnectionTest;
import tech.wenisch.contextcrate.backend.EndpointGuard;
import tech.wenisch.contextcrate.domain.CrateBackendSettings.S3Config;

/** Checks that an S3 endpoint can be reached, accepts the credentials, has the bucket and lets ContextCrate write, read and delete. */
@Component
public class StorageConnectionTester {
  static final int TIMEOUT_SECONDS = 10;
  private final EndpointGuard guard;

  public StorageConnectionTester(EndpointGuard guard) { this.guard = guard; }

  public ConnectionTest test(S3Config config) {
    var test = new ConnectionTest.Builder()
        .plan("input", "Settings are complete")
        .plan("reachable", "Endpoint reachable")
        .plan("bucket", "Credentials accepted and bucket found")
        .plan("write", "Write an object")
        .plan("read", "Read it back")
        .plan("delete", "Delete it");
    String key = (config.prefix() == null || config.prefix().isBlank() ? "" : config.prefix().strip().replaceAll("^/+|/+$", "") + "/")
        + ".contextcrate-connection-test/" + UUID.randomUUID();
    byte[] payload = ("contextcrate " + UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    S3Client[] client = new S3Client[1];
    try {
      test.run(() -> {
        require(config.bucket(), "A bucket name is required");
        require(config.accessKey(), "An access key is required");
        require(config.secretKey(), "A secret key is required");
        if (config.endpoint() != null && !config.endpoint().isBlank()) guard.check(config.endpoint(), false);
        client[0] = S3ArtifactStore.client(config, TIMEOUT_SECONDS);
        return null;
      });
      test.run(() -> {
        try {
          client[0].headBucket(b -> b.bucket(config.bucket()));
        } catch (S3Exception e) { // any HTTP answer proves the endpoint is reachable
          if (e.statusCode() >= 500) throw e;
        }
        return config.endpoint() == null || config.endpoint().isBlank() ? "AWS S3" : config.endpoint();
      });
      test.run(() -> {
        try {
          client[0].headBucket(b -> b.bucket(config.bucket()));
        } catch (S3Exception e) {
          if (e.statusCode() == 404) throw new IllegalStateException("The bucket '" + config.bucket() + "' does not exist. Create it first; ContextCrate does not create buckets for a crate.");
          if (e.statusCode() == 403 || e.statusCode() == 401) throw new IllegalStateException("Access denied: the access key or secret key is wrong, or the key may not use this bucket.");
          if (e.statusCode() == 301) throw new IllegalStateException("The bucket lives in another region. Set the region of the bucket.");
          throw e;
        }
        return config.bucket();
      });
      test.run(() -> {
        try {
          client[0].putObject(b -> b.bucket(config.bucket()).key(key), RequestBody.fromBytes(payload));
        } catch (S3Exception e) {
          if (e.statusCode() == 403) throw new IllegalStateException("The bucket is not writable with this key (access denied).");
          throw e;
        }
        return null;
      });
      test.run(() -> {
        byte[] read = client[0].getObjectAsBytes(b -> b.bucket(config.bucket()).key(key)).asByteArray();
        if (!java.util.Arrays.equals(read, payload)) throw new IllegalStateException("The object that was read back differs from the one that was written.");
        return null;
      });
      test.run(() -> {
        try {
          client[0].deleteObject(b -> b.bucket(config.bucket()).key(key));
        } catch (S3Exception e) {
          if (e.statusCode() == 403) throw new IllegalStateException("Objects can be written but not deleted with this key. ContextCrate needs delete rights to purge a crate.");
          throw e;
        }
        return null;
      });
    } finally {
      if (client[0] != null) client[0].close();
    }
    return test.build();
  }

  private static void require(String value, String message) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
  }
}
