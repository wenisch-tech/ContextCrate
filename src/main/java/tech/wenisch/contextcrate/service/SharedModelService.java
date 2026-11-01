package tech.wenisch.contextcrate.service;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.domain.SharedModel;
import tech.wenisch.contextcrate.repository.ProviderSettingsRepository;
import tech.wenisch.contextcrate.repository.SharedModelRepository;

/**
 * Administrator-managed model endpoints. The mutating and detail-returning methods are meant for
 * the admin panel only (callers must already have passed the admin check); the only thing a normal
 * user ever gets is {@link #options}, which carries id and name and nothing else.
 */
@Service
public class SharedModelService {
  /** What a crate owner may see of a shared model. Deliberately has no URL, model id or key. */
  public record Option(UUID id, String name) {}

  private final SharedModelRepository models;
  private final ProviderSettingsRepository providerSettings;

  public SharedModelService(SharedModelRepository models, ProviderSettingsRepository providerSettings) {
    this.models = models; this.providerSettings = providerSettings;
  }

  @Transactional(readOnly = true)
  public List<SharedModel> all() { return models.findAllByOrderByKindAscNameAsc(); }

  /** Models of {@code kind} a crate owner may pick, plus the one the crate already uses (even if since withdrawn). */
  @Transactional(readOnly = true)
  public List<Option> options(SharedModel.Kind kind, UUID currentlySelected) {
    List<Option> result = new ArrayList<>();
    for (var m : models.findByKindAndAvailableToUsersTrueOrderByNameAsc(kind)) result.add(new Option(m.getId(), m.getName()));
    if (currentlySelected != null && result.stream().noneMatch(o -> o.id().equals(currentlySelected)))
      models.findById(currentlySelected).filter(m -> m.getKind() == kind)
          .ifPresent(m -> result.add(new Option(m.getId(), m.getName())));
    return result;
  }

  @Transactional(readOnly = true)
  public String nameOf(UUID id) { return id == null ? null : models.findById(id).map(SharedModel::getName).orElse(null); }

  @Transactional
  public SharedModel create(SharedModel.Kind kind, String name, String baseUrl, String model, String apiKey,
      Integer dimensions, Integer maxInputCharacters, Integer timeoutSeconds, boolean availableToUsers,
      boolean defaultForNewCrates) {
    requireDefaultAllowed(availableToUsers, defaultForNewCrates);
    String cleanName = name == null ? "" : name.trim();
    validate(kind, cleanName, baseUrl, model, dimensions, maxInputCharacters, timeoutSeconds);
    if (models.existsByKindAndNameIgnoreCase(kind, cleanName))
      throw new IllegalArgumentException("A " + label(kind) + " model named \"" + cleanName + "\" already exists");
    var entity = new SharedModel(kind);
    entity.update(cleanName, baseUrl.trim(), model.trim(), apiKey, dimensions, maxInputCharacters, timeoutSeconds, availableToUsers, defaultForNewCrates);
    var saved = models.save(entity);
    if (saved.isDefaultForNewCrates()) clearOtherDefaults(saved);
    return saved;
  }

  /** @return {@code true} when the endpoint, model or vector size changed, so dependent crates need re-indexing. */
  @Transactional
  public boolean update(UUID id, String name, String baseUrl, String model, String apiKey,
      Integer dimensions, Integer maxInputCharacters, Integer timeoutSeconds, boolean availableToUsers,
      boolean defaultForNewCrates) {
    requireDefaultAllowed(availableToUsers, defaultForNewCrates);
    var entity = models.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown shared model"));
    String cleanName = name == null ? "" : name.trim();
    validate(entity.getKind(), cleanName, baseUrl, model, dimensions, maxInputCharacters, timeoutSeconds);
    if (!cleanName.equalsIgnoreCase(entity.getName()) && models.existsByKindAndNameIgnoreCase(entity.getKind(), cleanName))
      throw new IllegalArgumentException("A " + label(entity.getKind()) + " model named \"" + cleanName + "\" already exists");
    boolean material = !entity.getBaseUrl().equals(baseUrl.trim()) || !entity.getModel().equals(model.trim())
        || !java.util.Objects.equals(entity.getDimensions(), dimensions)
        || (apiKey != null && !apiKey.isBlank() && !apiKey.equals(entity.getApiKey()));
    entity.update(cleanName, baseUrl.trim(), model.trim(), apiKey, dimensions, maxInputCharacters, timeoutSeconds, availableToUsers, defaultForNewCrates);
    if (entity.isDefaultForNewCrates()) clearOtherDefaults(entity);
    return material;
  }

  @Transactional
  public void delete(UUID id) {
    var entity = models.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown shared model"));
    int uses = crateIdsUsing(entity).size();
    if (uses > 0) throw new IllegalStateException("\"" + entity.getName() + "\" is used by " + uses
        + (uses == 1 ? " crate" : " crates") + ". Make it unavailable instead, or switch those crates first.");
    models.delete(entity);
  }

  @Transactional(readOnly = true)
  public List<UUID> crateIdsUsing(UUID id) {
    return models.findById(id).map(this::crateIdsUsing).orElse(List.of());
  }

  @Transactional(readOnly = true)
  public int usageCount(SharedModel model) { return crateIdsUsing(model).size(); }

  private List<UUID> crateIdsUsing(SharedModel model) {
    var rows = switch (model.getKind()) {
      case EMBEDDING -> providerSettings.findByEmbeddingSharedModelId(model.getId());
      case RERANKING -> providerSettings.findByRerankingSharedModelId(model.getId());
      case ANSWER -> providerSettings.findByAnsweringSharedModelId(model.getId());
    };
    return rows.stream().map(p -> p.getCrateId()).toList();
  }

  /** Only one default per type; setting a new one retires the previous one. */
  private void clearOtherDefaults(SharedModel keep) {
    for (var other : models.findByKindAndDefaultForNewCratesTrue(keep.getKind()))
      if (!other.getId().equals(keep.getId())) other.clearDefault();
  }

  private static void requireDefaultAllowed(boolean availableToUsers, boolean defaultForNewCrates) {
    if (defaultForNewCrates && !availableToUsers)
      throw new IllegalArgumentException("A default model must be available to users");
  }

  private static void validate(SharedModel.Kind kind, String name, String baseUrl, String model,
      Integer dimensions, Integer maxInputCharacters, Integer timeoutSeconds) {
    if (name.isEmpty() || name.length() > 120) throw new IllegalArgumentException("Name is required (max 120 characters)");
    if (model == null || model.isBlank()) throw new IllegalArgumentException("Model identifier is required");
    if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("Endpoint URL is required");
    try {
      URI uri = URI.create(baseUrl.trim());
      if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())))
        throw new IllegalArgumentException("Endpoint URL must be an http(s) URL");
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Endpoint URL must be an http(s) URL");
    }
    if (kind == SharedModel.Kind.EMBEDDING && (dimensions == null || dimensions < 1))
      throw new IllegalArgumentException("Vector dimensions must be positive");
    if (kind != SharedModel.Kind.ANSWER && (maxInputCharacters == null || maxInputCharacters < 256))
      throw new IllegalArgumentException("Maximum input characters must be at least 256");
    if (kind != SharedModel.Kind.EMBEDDING && timeoutSeconds != null && timeoutSeconds < 5)
      throw new IllegalArgumentException("Timeout must be at least 5 seconds");
  }

  private static String label(SharedModel.Kind kind) {
    return switch (kind) { case EMBEDDING -> "embedding"; case RERANKING -> "reranking"; case ANSWER -> "answer"; };
  }
}
