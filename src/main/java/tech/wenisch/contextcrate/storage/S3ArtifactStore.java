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
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpConfigurationOption;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.utils.AttributeMap;
import tech.wenisch.contextcrate.domain.CrateBackendSettings;
import tech.wenisch.contextcrate.util.InsecureSsl;

@Component
@ConditionalOnProperty(name = "contextcrate.artifacts.backend", havingValue = "s3")
public class S3ArtifactStore implements ArtifactStore {
  private final S3Client client;
  private final String bucket;
  private final String prefix;

  /** The installation's storage: default credential chain, and the bucket is created when it is missing. */
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

  /** A crate's own storage: explicit credentials, and the bucket has to exist already. */
  public S3ArtifactStore(CrateBackendSettings.S3Config config) {
    client = client(config, 60);
    bucket = config.bucket();
    prefix = config.prefix() == null ? "" : config.prefix().replaceAll("^/+|/+$", "");
  }

  /** An S3 client for explicit settings, used for a crate's storage and for the connection test. */
  public static S3Client client(CrateBackendSettings.S3Config c, int timeoutSeconds) {
    var builder =
        S3Client.builder()
            .region(software.amazon.awssdk.regions.Region.of(c.region() == null || c.region().isBlank() ? "us-east-1" : c.region()))
            .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(c.accessKey(), c.secretKey())))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(c.pathStyle()).build())
            // Many S3-compatible services do not accept the additional checksums newer SDKs send by default.
            .requestChecksumCalculation(software.amazon.awssdk.core.checksums.RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(software.amazon.awssdk.core.checksums.ResponseChecksumValidation.WHEN_REQUIRED)
            .overrideConfiguration(o -> o.apiCallTimeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .apiCallAttemptTimeout(java.time.Duration.ofSeconds(timeoutSeconds)));
    if (c.endpoint() != null && !c.endpoint().isBlank()) builder.endpointOverride(URI.create(c.endpoint().strip()));
    // The crate's own setting or the installation-wide contextcrate.tls.trust-all-certificates switch.
    // The SDK's HTTP client keeps its own TLS settings, so the JVM-wide default does not reach it.
    if (c.trustAllCertificates() || InsecureSsl.globalTrustAll()) builder.httpClientBuilder(new TrustAllHttpClientBuilder());
    return builder.build();
  }

  /** Builds the SDK's HTTP client with certificate and host name validation switched off; the SDK closes the client it builds. */
  static final class TrustAllHttpClientBuilder implements SdkHttpClient.Builder<TrustAllHttpClientBuilder> {
    @Override
    public SdkHttpClient buildWithDefaults(AttributeMap serviceDefaults) {
      AttributeMap trustAll = AttributeMap.builder().put(SdkHttpConfigurationOption.TRUST_ALL_CERTIFICATES, true).build();
      return UrlConnectionHttpClient.builder().buildWithDefaults(trustAll.merge(serviceDefaults));
    }
  }

  public void close() { client.close(); }

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
