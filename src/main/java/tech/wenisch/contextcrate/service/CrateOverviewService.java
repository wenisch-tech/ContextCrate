package tech.wenisch.contextcrate.service;

import java.util.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import tech.wenisch.contextcrate.domain.Crate;
import tech.wenisch.contextcrate.domain.CrateMember;
import tech.wenisch.contextcrate.domain.NormalizedDocument;
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
      Crate crate, CrateMember.Role role, long documents, long chunks, long sources, long members,
      IngestionSparkline ingestion) {
    public String createdOn() { return DAY.format(crate.getCreatedAt()); }

    public String updatedOn() { return DAY.format(crate.getUpdatedAt()); }
  }

  public record IngestionSparkline(String points, long ingestedLastSevenDays, boolean hasActivity) { }

  private final CrateRepository crates;
  private final CrateMemberRepository members;
  private final SourceRepository sources;
  private final NormalizedDocumentRepository documents;
  private final DocumentChunkRepository chunks;
  private final CrateAccessService access;

  public CrateOverviewService(CrateRepository crates, CrateMemberRepository members,
      SourceRepository sources, NormalizedDocumentRepository documents, DocumentChunkRepository chunks,
      CrateAccessService access) {
    this.crates = crates; this.members = members; this.sources = sources;
    this.documents = documents; this.chunks = chunks; this.access = access;
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
    Map<UUID, Long> chunkCounts = totals(chunks.countByCrate(ids));
    Map<UUID, Long> sourceCounts = totals(sources.countByCrate(ids));
    Map<UUID, Long> memberCounts = totals(members.countByCrate(ids));
    Map<UUID, IngestionSparkline> activity = ingestionSparklines(ids);
    return selection.stream()
        .map(crate -> new CrateCard(
            crate,
            roles.get(crate.getId()),
            documentCounts.getOrDefault(crate.getId(), 0L),
            chunkCounts.getOrDefault(crate.getId(), 0L),
            sourceCounts.getOrDefault(crate.getId(), 0L),
            memberCounts.getOrDefault(crate.getId(), 0L),
            activity.getOrDefault(crate.getId(), emptySparkline())))
        .toList();
  }

  private Map<UUID, IngestionSparkline> ingestionSparklines(List<UUID> crateIds) {
    ZoneId zone = ZoneId.systemDefault();
    LocalDate today = LocalDate.now(zone);
    Instant since = today.minusDays(6).atStartOfDay(zone).toInstant();
    Instant until = today.plusDays(1).atStartOfDay(zone).toInstant();
    Map<UUID, long[]> buckets = new HashMap<>();
    for (NormalizedDocument document : documents
        .findByCrateIdInAndCurrentVersionTrueAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            crateIds, since, until)) {
      int day = (int) ChronoUnit.DAYS.between(today.minusDays(6),
          document.getCreatedAt().atZone(zone).toLocalDate());
      if (day >= 0 && day < 7) buckets.computeIfAbsent(document.getCrateId(), ignored -> new long[7])[day]++;
    }
    Map<UUID, IngestionSparkline> result = new HashMap<>();
    buckets.forEach((crateId, values) -> result.put(crateId, sparkline(values)));
    return result;
  }

  private static IngestionSparkline emptySparkline() { return sparkline(new long[7]); }

  private static IngestionSparkline sparkline(long[] values) {
    long maximum = Arrays.stream(values).max().orElse(0), total = Arrays.stream(values).sum();
    StringBuilder points = new StringBuilder();
    for (int index = 0; index < values.length; index++) {
      if (index > 0) points.append(' ');
      double x = index * 20d;
      double y = maximum == 0 ? 25d : 27d - values[index] * 23d / maximum;
      points.append(String.format(Locale.ROOT, "%.1f,%.1f", x, y));
    }
    return new IngestionSparkline(points.toString(), total, total > 0);
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
