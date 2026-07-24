package tech.wenisch.contextcrate.storage;

import java.io.*;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import tech.wenisch.contextcrate.config.ContextCrateProperties;

@Component
@ConditionalOnProperty(name = "contextcrate.artifacts.backend", havingValue = "s3")
public class S3ArtifactStore implements ArtifactStore {
  private final S3Client client;
  private final String bucket;
  private final String prefix;

  public S3ArtifactStore(ContextCrateProperties p) {
    var a = p.artifacts();
    var builder =
        S3Client.builder()
            .region(
                software.amazon.awssdk.regions.Region.of(
                    a.region() == null ? "us-east-1" : a.region()))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
    if (a.endpoint() != null && !a.endpoint().isBlank())
      builder.endpointOverride(URI.create(a.endpoint()));
    client = builder.build();
    bucket = a.bucket();
    prefix = a.prefix() == null ? "" : a.prefix().replaceAll("/+$", "");
    try {
      client.headBucket(b -> b.bucket(bucket));
    } catch (S3Exception e) {
      client.createBucket(b -> b.bucket(bucket));
    }
  }

  @Override
  public ArtifactMetadata put(String key, InputStream content, long max) throws IOException {
    byte[] bytes = content.readNBytes(Math.toIntExact(Math.min(max + 1, Integer.MAX_VALUE)));
    if (bytes.length > max) throw new IOException("Artifact exceeds configured maximum");
    String sha = Hashing.sha256(bytes);
    client.putObject(
        b -> b.bucket(bucket).key(key(key)).metadata(java.util.Map.of("sha256", sha)),
        RequestBody.fromBytes(bytes));
    return new ArtifactMetadata(key, sha, bytes.length);
  }

  @Override
  public InputStream open(String key) {
    return client.getObject(b -> b.bucket(bucket).key(key(key)));
  }

  @Override
  public boolean exists(String key) {
    try {
      client.headObject(b -> b.bucket(bucket).key(key(key)));
      return true;
    } catch (NoSuchKeyException e) {
      return false;
    } catch (S3Exception e) {
      return e.statusCode() != 404;
    }
  }

  @Override
  public void delete(String key) {
    client.deleteObject(b -> b.bucket(bucket).key(key(key)));
  }

  @Override
  public void deletePrefix(String value) {
    String full = key(value).replaceAll("/+$", "") + "/";
    String token = null;
    do {
      var request = ListObjectsV2Request.builder().bucket(bucket).prefix(full);
      if (token != null) request.continuationToken(token);
      var response = client.listObjectsV2(request.build());
      if (!response.contents().isEmpty())
        client.deleteObjects(b -> b.bucket(bucket).delete(d -> d.objects(
            response.contents().stream().map(o -> ObjectIdentifier.builder().key(o.key()).build()).toList())));
      token = response.nextContinuationToken();
    } while (token != null);
  }

  private String key(String key) {
    return prefix.isBlank() ? key : prefix + "/" + key;
  }
}
