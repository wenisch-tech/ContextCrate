package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A crate that does not use the global artifact storage and/or the global search backend. The secrets are
 * stored like the other per-crate provider keys, are write-only in the UI and API, and are never returned.
 */
@Entity
@Table(name = "crate_backend_settings")
public class CrateBackendSettings {
  public enum StorageMode { GLOBAL, S3 }
  public enum IndexMode { GLOBAL, CUSTOM }

  @Id @Column(name = "crate_id") private UUID crateId;
  @Enumerated(EnumType.STRING) @Column(name = "storage_mode", nullable = false, length = 16) private StorageMode storageMode = StorageMode.GLOBAL;
  @Column(name = "s3_endpoint") private String s3Endpoint;
  @Column(name = "s3_region") private String s3Region;
  @Column(name = "s3_bucket") private String s3Bucket;
  @Column(name = "s3_prefix") private String s3Prefix;
  @Column(name = "s3_access_key") private String s3AccessKey;
  @Column(name = "s3_secret_key") private String s3SecretKey;
  @Column(name = "s3_path_style", nullable = false) private boolean s3PathStyle = true;
  @Enumerated(EnumType.STRING) @Column(name = "index_mode", nullable = false, length = 16) private IndexMode indexMode = IndexMode.GLOBAL;
  @Column(name = "index_backend") private String indexBackend;
  @Column(name = "index_endpoint") private String indexEndpoint;
  @Column(name = "index_api_key") private String indexApiKey;
  @Column(name = "index_username") private String indexUsername;
  @Column(name = "index_password") private String indexPassword;
  @Column(name = "index_prefix") private String indexPrefix;
  @Column(name = "index_trust_all", nullable = false) private boolean indexTrustAll;
  @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

  protected CrateBackendSettings() {}

  public CrateBackendSettings(UUID crateId) { this.crateId = crateId; }

  /** Dedicated S3 storage. */
  public CrateBackendSettings s3(S3Config config) {
    this.storageMode = StorageMode.S3;
    this.s3Endpoint = config.endpoint(); this.s3Region = config.region(); this.s3Bucket = config.bucket(); this.s3Prefix = config.prefix();
    this.s3AccessKey = config.accessKey(); this.s3SecretKey = config.secretKey(); this.s3PathStyle = config.pathStyle();
    return this;
  }

  /** Dedicated search backend. */
  public CrateBackendSettings index(IndexConfig config) {
    this.indexMode = IndexMode.CUSTOM;
    this.indexBackend = config.backend(); this.indexEndpoint = config.endpoint(); this.indexApiKey = config.apiKey();
    this.indexUsername = config.username(); this.indexPassword = config.password(); this.indexPrefix = config.prefix();
    this.indexTrustAll = config.trustAllCertificates();
    return this;
  }

  public UUID getCrateId() { return crateId; }
  public StorageMode getStorageMode() { return storageMode; }
  public IndexMode getIndexMode() { return indexMode; }
  public boolean customStorage() { return storageMode == StorageMode.S3; }
  public boolean customIndex() { return indexMode == IndexMode.CUSTOM; }
  public Instant getCreatedAt() { return createdAt; }

  public S3Config s3Config() {
    return new S3Config(s3Endpoint, s3Region, s3Bucket, s3Prefix, s3AccessKey, s3SecretKey, s3PathStyle);
  }

  public IndexConfig indexConfig() {
    return new IndexConfig(indexBackend, indexEndpoint, indexApiKey, indexUsername, indexPassword, indexPrefix, indexTrustAll);
  }

  /** Dedicated S3 storage; {@code toString} never prints the secret. */
  public record S3Config(String endpoint, String region, String bucket, String prefix, String accessKey, String secretKey, boolean pathStyle) {
    @Override public String toString() { return "S3Config[endpoint=" + endpoint + ", bucket=" + bucket + ", prefix=" + prefix + "]"; }
    /** Changes of the connection invalidate cached clients. */
    public String fingerprint() { return String.join("|", String.valueOf(endpoint), String.valueOf(region), String.valueOf(bucket), String.valueOf(prefix), String.valueOf(accessKey), String.valueOf(secretKey), String.valueOf(pathStyle)); }
  }

  /** Dedicated search backend; {@code toString} never prints secrets. */
  public record IndexConfig(String backend, String endpoint, String apiKey, String username, String password, String prefix, boolean trustAllCertificates) {
    @Override public String toString() { return "IndexConfig[backend=" + backend + ", endpoint=" + endpoint + ", prefix=" + prefix + "]"; }
    public String fingerprint() { return String.join("|", String.valueOf(backend), String.valueOf(endpoint), String.valueOf(apiKey), String.valueOf(username), String.valueOf(password), String.valueOf(prefix), String.valueOf(trustAllCertificates)); }
  }
}
