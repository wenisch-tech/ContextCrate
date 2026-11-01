package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * An administrator-managed model endpoint that crate owners can select by name. The endpoint URL
 * and API key never leave the server: only {@link #getId()}, {@link #getName()} and
 * {@link #getKind()} are ever shown to non-administrators.
 */
@Entity
@Table(name = "shared_model")
public class SharedModel {
  public enum Kind { EMBEDDING, RERANKING, ANSWER }

  @Id private UUID id;
  @Column(nullable = false, length = 120) private String name;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Kind kind;
  @Column(name = "base_url", nullable = false, length = 2000) private String baseUrl;
  @Column(nullable = false, length = 500) private String model;
  @Column(name = "api_key", length = 2000) private String apiKey;
  private Integer dimensions;
  @Column(name = "max_input_characters") private Integer maxInputCharacters;
  @Column(name = "timeout_seconds") private Integer timeoutSeconds;
  @Column(name = "available_to_users", nullable = false) private boolean availableToUsers;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected SharedModel() {}

  public SharedModel(Kind kind) {
    this.id = UUID.randomUUID(); this.kind = kind; this.createdAt = Instant.now(); this.updatedAt = createdAt;
  }

  /** A blank {@code apiKey} keeps the stored key, matching the per-crate provider forms. */
  public void update(String name, String baseUrl, String model, String apiKey, Integer dimensions,
      Integer maxInputCharacters, Integer timeoutSeconds, boolean availableToUsers) {
    this.name = name; this.baseUrl = baseUrl; this.model = model;
    if (apiKey != null && !apiKey.isBlank()) this.apiKey = apiKey;
    this.dimensions = dimensions; this.maxInputCharacters = maxInputCharacters;
    this.timeoutSeconds = timeoutSeconds; this.availableToUsers = availableToUsers;
    this.updatedAt = Instant.now();
  }

  public UUID getId() { return id; }
  public String getName() { return name; }
  public Kind getKind() { return kind; }
  public String getBaseUrl() { return baseUrl; }
  public String getModel() { return model; }
  public String getApiKey() { return apiKey; }
  public boolean hasApiKey() { return apiKey != null && !apiKey.isBlank(); }
  public Integer getDimensions() { return dimensions; }
  public Integer getMaxInputCharacters() { return maxInputCharacters; }
  public Integer getTimeoutSeconds() { return timeoutSeconds; }
  public boolean isAvailableToUsers() { return availableToUsers; }
}
