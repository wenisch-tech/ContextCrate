package tech.wenisch.contextcrate.backend;

import java.util.Locale;
import tech.wenisch.contextcrate.domain.CrateBackendSettings.IndexConfig;
import tech.wenisch.contextcrate.domain.CrateBackendSettings.S3Config;

/**
 * What the creator of a crate chose for storage and search. Both parts are optional; a missing part or
 * the mode {@code GLOBAL} means "use the installation's provider".
 */
public record BackendChoice(StorageChoice storage, SearchChoice searchBackend) {
  public static final BackendChoice GLOBAL = new BackendChoice(null, null);

  public boolean customStorage() { return storage != null && storage.custom(); }
  public boolean customSearch() { return searchBackend != null && searchBackend.custom(); }

  /** @param mode {@code GLOBAL} or {@code S3} */
  public record StorageChoice(String mode, String endpoint, String region, String bucket, String prefix, String accessKey, String secretKey, Boolean pathStyle) {
    public boolean custom() { return "S3".equalsIgnoreCase(mode); }

    public S3Config toConfig() {
      return new S3Config(blankToNull(endpoint), blankToNull(region) == null ? "us-east-1" : region.strip(), trim(bucket), blankToNull(prefix),
          trim(accessKey), secretKey == null || secretKey.isBlank() ? null : secretKey, pathStyle == null || pathStyle);
    }

    @Override public String toString() { return "StorageChoice[mode=" + mode + ", endpoint=" + endpoint + ", bucket=" + bucket + "]"; }
  }

  /** @param mode {@code GLOBAL} or {@code CUSTOM}; @param backend opensearch, elasticsearch, qdrant or pgvector */
  public record SearchChoice(String mode, String backend, String endpoint, String apiKey, String username, String password, String prefix, Boolean trustAllCertificates) {
    public boolean custom() { return "CUSTOM".equalsIgnoreCase(mode); }

    public IndexConfig toConfig() {
      return new IndexConfig(backend == null ? null : backend.strip().toLowerCase(Locale.ROOT), trim(endpoint), blankToNull(apiKey), blankToNull(username),
          password == null || password.isEmpty() ? null : password, blankToNull(prefix), trustAllCertificates != null && trustAllCertificates);
    }

    @Override public String toString() { return "SearchChoice[mode=" + mode + ", backend=" + backend + ", endpoint=" + endpoint + "]"; }
  }

  private static String trim(String value) { return value == null ? null : value.strip(); }

  private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.strip(); }
}
