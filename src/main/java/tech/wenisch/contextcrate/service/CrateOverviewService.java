package tech.wenisch.contextcrate.service;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import tech.wenisch.contextcrate.domain.Crate;
import tech.wenisch.contextcrate.domain.CrateMember;
import tech.wenisch.contextcrate.repository.*;

/**
 * Builds the crate cards shown on the start page and in the administration panel. Counts are
 * resolved with one grouped query per metric instead of per-crate lookups.
 */
@Service
public class CrateOverviewService {
  private static final java.time.format.DateTimeFormatter DAY =
      java.time.format.DateTimeFormatter.ISO_LOCAL_DATE.withZone(java.time.ZoneId.systemDefault());

  /**
   * A crate plus the viewer's role in it and its content metrics. {@code role} is null when an
   * administrator sees a crate they are not a member of. The date accessors exist because
   * {@code thymeleaf-extras-java8time} is not on the classpath, so templates cannot format an
   * {@link java.time.Instant} themselves.
   */
  public record CrateCard(
      Crate crate, CrateMember.Role role, long documents, long sources, long members) {
    public String createdOn() { return DAY.format(crate.getCreatedAt()); }

    public String updatedOn() { return DAY.format(crate.getUpdatedAt()); }
  }

  private final CrateRepository crates;
  private final CrateMemberRepository members;
  private final SourceRepository sources;
  private final NormalizedDocumentRepository documents;
  private final CrateAccessService access;

  public CrateOverviewService(CrateRepository crates, CrateMemberRepository members,
      SourceRepository sources, NormalizedDocumentRepository documents, CrateAccessService access) {
    this.crates = crates; this.members = members; this.sources = sources;
    this.documents = documents; this.access = access;
  }

  /** Cards for the crates the current user belongs to, sorted by name. */
  public List<CrateCard> accessible() {
    Map<UUID, CrateMember.Role> roles = access.memberships().stream()
        .collect(Collectors.toMap(CrateMember::getCrateId, CrateMember::getRole, (a, b) -> a));
    return cards(byName(crates.findAllById(roles.keySet())), roles);
  }

  /** Cards for every crate in the installation, sorted by name. Administrators only. */
  public List<CrateCard> all() {
    Map<UUID, CrateMember.Role> roles = access.memberships().stream()
        .collect(Collectors.toMap(CrateMember::getCrateId, CrateMember::getRole, (a, b) -> a));
    return cards(byName(crates.findAll()), roles);
  }

  private List<CrateCard> cards(List<Crate> selection, Map<UUID, CrateMember.Role> roles) {
    if (selection.isEmpty()) return List.of();
    List<UUID> ids = selection.stream().map(Crate::getId).toList();
    Map<UUID, Long> documentCounts = totals(documents.countCurrentByCrate(ids));
    Map<UUID, Long> sourceCounts = totals(sources.countByCrate(ids));
    Map<UUID, Long> memberCounts = totals(members.countByCrate(ids));
    return selection.stream()
        .map(crate -> new CrateCard(
            crate,
            roles.get(crate.getId()),
            documentCounts.getOrDefault(crate.getId(), 0L),
            sourceCounts.getOrDefault(crate.getId(), 0L),
            memberCounts.getOrDefault(crate.getId(), 0L)))
        .toList();
  }

  private static List<Crate> byName(Iterable<Crate> selection) {
    List<Crate> values = new ArrayList<>();
    selection.forEach(values::add);
    values.sort(Comparator.comparing(Crate::getName, String.CASE_INSENSITIVE_ORDER));
    return values;
  }

  private static Map<UUID, Long> totals(List<CrateCount> counts) {
    return counts.stream().collect(Collectors.toMap(CrateCount::getCrateId, CrateCount::getTotal));
  }
}
