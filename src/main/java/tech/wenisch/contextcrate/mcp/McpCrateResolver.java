package tech.wenisch.contextcrate.mcp;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.domain.Crate;
import tech.wenisch.contextcrate.domain.CrateMember;
import tech.wenisch.contextcrate.repository.CrateRepository;
import tech.wenisch.contextcrate.security.ApiKeyPrincipal;
import tech.wenisch.contextcrate.service.CrateAccessService;
import tech.wenisch.contextcrate.service.CrateService;

/**
 * Decides which crate a tool call addresses and verifies access to it.
 *
 * <p>Crate-scoped API keys are the credential an external AI client is normally given, and they are
 * awkward here: {@link CrateAccessService#memberships()} returns nothing for them and {@code
 * currentUser()} throws. Their crate is therefore taken from the principal itself, and neither
 * method is called on that path.
 */
@Component
public class McpCrateResolver {
  private final CrateService crates;
  private final CrateAccessService access;
  private final CrateRepository repository;

  public McpCrateResolver(CrateService crates, CrateAccessService access, CrateRepository repository) {
    this.crates = crates;
    this.access = access;
    this.repository = repository;
  }

  /** Thrown when no crate can be determined; surfaces to the model as a tool error, not a fault. */
  public static class UnresolvedCrateException extends RuntimeException {
    public UnresolvedCrateException(String message) {
      super(message);
    }
  }

  /**
   * Resolves the crate for a call and asserts at least viewer access.
   *
   * @param pathCrateId crate from the request path, or null on the global endpoint
   * @param requested the tool's {@code crate} argument: an id or a name, or null
   */
  public Crate resolve(UUID pathCrateId, String requested) {
    UUID crateId = pathCrateId != null ? pathCrateId : fromArgumentOrCredential(requested);
    access.require(crateId, CrateMember.Role.VIEWER);
    return repository
        .findById(crateId)
        .orElseThrow(() -> new UnresolvedCrateException("Unknown crate " + crateId));
  }

  /** The crates this caller may address, honouring the crate-scoped-key special case. */
  public List<Crate> available() {
    Optional<UUID> bound = boundCrateId();
    if (bound.isPresent()) return repository.findById(bound.get()).map(List::of).orElseGet(List::of);
    return crates.accessible();
  }

  private UUID fromArgumentOrCredential(String requested) {
    if (requested != null && !requested.isBlank()) return byIdOrName(requested.trim());
    Optional<UUID> bound = boundCrateId();
    if (bound.isPresent()) return bound.get();
    List<Crate> accessible = crates.accessible();
    if (accessible.size() == 1) return accessible.getFirst().getId();
    throw new UnresolvedCrateException(
        accessible.isEmpty()
            ? "No crate is accessible with this credential."
            : "Several crates are accessible. Pass the \"crate\" argument, or call list_crates first.");
  }

  private UUID byIdOrName(String requested) {
    try {
      return UUID.fromString(requested);
    } catch (IllegalArgumentException notAnId) {
      return available().stream()
          .filter(crate -> crate.getName().equalsIgnoreCase(requested))
          .findFirst()
          .map(Crate::getId)
          .orElseThrow(
              () ->
                  new UnresolvedCrateException(
                      "No crate named \"" + requested + "\". Call list_crates for the available ones."));
    }
  }

  /** The crate a crate-scoped API key is pinned to, if the caller presented one. */
  private Optional<UUID> boundCrateId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null) return Optional.empty();
    return authentication.getPrincipal() instanceof ApiKeyPrincipal key && !key.personal()
        ? Optional.ofNullable(key.crateId())
        : Optional.empty();
  }
}
